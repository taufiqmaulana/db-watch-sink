package io.datasync.server;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** The management flow end to end: sign in, configure, dry run, run, and role checks. */
@QuarkusTest
class ApiTest {

    static final String API = "/api/v1";
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    static {
        PG.start();
    }

    @Inject
    MockMailbox mailbox;

    @Inject
    Housekeeping housekeeping;

    @Inject
    Db db;

    @Test
    void managesAndRunsAJob() throws Exception {
        sql("CREATE TABLE items (id int PRIMARY KEY, label text)", "INSERT INTO items VALUES (1, 'a'), (2, 'b')");

        RestAssured.given().basePath(API).get("/jobs").then().statusCode(401);
        String admin = login("admin", "bootstrap-secret", true);
        as(admin).get("/jobs").then().statusCode(403).body("message", containsString("Change your password"));
        as(admin).body(Map.of("currentPassword", "bootstrap-secret", "newPassword", "admin-password-2"))
            .post("/auth/password").then().statusCode(200).body("mustChangePassword", equalTo(false));

        String connId = as(admin).body(Map.of("name", "pg", "kind", "jdbc-postgres", "role", "BOTH",
                "url", PG.getJdbcUrl(), "username", PG.getUsername(), "password", PG.getPassword()))
            .post("/connections").then().statusCode(201)
            .body("hasPassword", equalTo(true)).body("$", not(hasKey("password")))
            .extract().path("id");
        as(admin).post("/connections/" + connId + "/test").then().statusCode(200).body("ok", equalTo(true));
        as(admin).queryParam("schema", "public").get("/connections/" + connId + "/objects")
            .then().statusCode(200).body("name", hasItem("items"));

        Map<String, Object> config = Map.of(
            "name", "items_sync",
            "source", Map.of("connection", connId, "object", "public.items"),
            "columns", Map.of("rename", Map.of("label", "title")),
            "sinks", List.of(Map.of("type", "jdbc", "connection", connId, "target", "public.items_copy")));
        as(admin).body(config).post("/jobs/validate").then().statusCode(200)
            .body("rows.size()", equalTo(2)).body("columns.target", equalTo(List.of("id", "title")));

        String jobId = as(admin).body(Map.of("config", config)).post("/jobs").then().statusCode(201).extract().path("id");
        as(admin).post("/jobs/" + jobId + "/run").then().statusCode(202);
        awaitRunFinished(admin, jobId);
        as(admin).get("/jobs/" + jobId).then().body("lastRun.state", equalTo("SUCCEEDED")).body("lastRun.upserts", equalTo(2));
        assertThat(query("SELECT string_agg(title, ',' ORDER BY id) FROM items_copy")).isEqualTo("a,b");

        as(admin).body(Map.of("config", config, "version", 99)).put("/jobs/" + jobId).then().statusCode(409);

        // a sink added to a job that already ran gets the existing rows, not only later changes
        Map<String, Object> twoSinks = new HashMap<>(config);
        twoSinks.put("sinks", List.of(
            Map.of("type", "jdbc", "connection", connId, "target", "public.items_copy"),
            Map.of("type", "jdbc", "connection", connId, "target", "public.items_copy2")));
        as(admin).body(Map.of("config", twoSinks, "version", 1)).put("/jobs/" + jobId).then().statusCode(200);
        as(admin).post("/jobs/" + jobId + "/run").then().statusCode(202);
        awaitRunFinished(admin, jobId);
        assertThat(query("SELECT string_agg(title, ',' ORDER BY id) FROM items_copy2")).isEqualTo("a,b");

        // scheduled runs fire on their own, and pausing stops them
        Map<String, Object> scheduled = new HashMap<>(twoSinks);
        scheduled.put("every", "10s");
        scheduled.put("jitterPercent", 0);
        as(admin).body(Map.of("config", scheduled, "version", 2)).put("/jobs/" + jobId).then().statusCode(200).body("nextRunAt", notNullValue());
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        while (as(admin).get("/jobs/" + jobId + "/runs").then().extract().jsonPath().getList("$").size() < 3) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("no scheduled run within 25 s");
            Thread.sleep(500);
        }
        as(admin).post("/jobs/" + jobId + "/pause").then().statusCode(200).body("nextRunAt", nullValue());

        // incremental modes need a watermark column; a reconcile runs on demand, even while paused
        Map<String, Object> watermark = new HashMap<>(scheduled);
        watermark.put("mode", "WATERMARK");
        as(admin).body(Map.of("config", watermark, "version", 3)).put("/jobs/" + jobId).then().statusCode(400)
            .body("message", equalTo("Watermark column is required"));
        as(admin).post("/jobs/" + jobId + "/reconcile").then().statusCode(202);
        awaitRunFinished(admin, jobId);
        as(admin).get("/jobs/" + jobId + "/runs").then().body("[0].kind", equalTo("RECONCILE")).body("[0].state", equalTo("SUCCEEDED"));
        as(admin).delete("/connections/" + connId).then().statusCode(409).body("message", containsString("items_sync"));

        as(admin).body(Map.of("username", "vera", "password", "viewer-password-1", "role", "VIEWER")).post("/users").then().statusCode(201);
        String viewer = login("vera", "viewer-password-1", true);
        as(viewer).body(Map.of("currentPassword", "viewer-password-1", "newPassword", "viewer-password-2")).post("/auth/password").then().statusCode(200);
        as(viewer).get("/jobs").then().statusCode(200);
        as(viewer).post("/jobs/" + jobId + "/run").then().statusCode(403).body("message", containsString("operator"));
        as(viewer).get("/users").then().statusCode(403);

        as(admin).get("/audit").then().statusCode(200).body("action", hasItem("job.run"));
        as(admin).get("/overview").then().statusCode(200).body("jobs", equalTo(1)).body("runs24h", greaterThanOrEqualTo(3));
        RestAssured.given().get("/metrics").then().statusCode(200).body(containsString("jvm_memory_used_bytes"));

        deadLettersAreRetriedOnceTheWebhookIsBack(admin, connId);
        alertsMetricsAndConfigAsCode(admin, connId);
    }

    /** Design 12 and 13: alert channels and rules, engine metrics, YAML export and import. */
    private void alertsMetricsAndConfigAsCode(String admin, String connId) throws Exception {
        as(admin).get("/settings").then().statusCode(200).body("alerts.failedRuns", equalTo(3)).body("retentionDays", equalTo(30));
        List<String> alerts = new CopyOnWriteArrayList<>();
        HttpServer channel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        channel.createContext("/alerts", exchange -> {
            alerts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        channel.start();
        try {
            Map<String, Object> rules = Map.of("emails", List.of("ops@example.com"),
                "webhookUrl", "http://127.0.0.1:" + channel.getAddress().getPort() + "/alerts",
                "failedRuns", 1, "lagMinutes", 0, "deadLetters", true, "deleteGuard", true);
            as(admin).body(Map.of("alerts", Map.of("emails", List.of("not-an-address")), "retentionDays", 30)).put("/settings")
                .then().statusCode(400).body("message", containsString("not-an-address"));
            as(admin).body(rules).post("/settings/alerts/test").then().statusCode(200).body("sent", equalTo(List.of("email", "webhook")));
            assertThat(mailbox.getMailsSentTo("ops@example.com")).hasSize(1);
            as(admin).body(Map.of("alerts", rules, "retentionDays", 14)).put("/settings").then().statusCode(200).body("retentionDays", equalTo(14));

            // a run that fails raises the "failed 1 run in a row" alert by email and webhook
            Map<String, Object> broken = Map.of("name", "broken", "source", Map.of("connection", connId, "object", "public.missing"),
                "sinks", List.of(Map.of("type", "jdbc", "connection", connId, "target", "public.broken_copy")));
            String jobId = as(admin).body(Map.of("config", broken)).post("/jobs").then().statusCode(201).extract().path("id");
            as(admin).post("/jobs/" + jobId + "/run").then().statusCode(202);
            awaitRunFinished(admin, jobId);
            assertThat(alerts).anySatisfy(a -> assertThat(a).contains("JOB_FAILED").contains("Job broken failed 1 run in a row"));
            assertThat(mailbox.getMailsSentTo("ops@example.com")).hasSize(2);
            as(admin).get("/audit").then().body("action", hasItem("alert.job-failed"));

            String metrics = RestAssured.given().get("/metrics").then().statusCode(200).extract().asString();
            assertThat(metrics).contains("datasync_run_duration_seconds_count{job=\"broken\"}")
                .contains("datasync_rows_total{job=\"items_sync\",kind=\"upsert\"}")
                .contains("datasync_lag_seconds{job=\"items_sync\"}");

            // export, then import it back: unchanged except the fixed job; a dry run keeps nothing
            String yaml = as(admin).get("/export").then().statusCode(200).extract().asString();
            assertThat(yaml).contains("name: broken").contains("connection: pg").contains("${env:PG_PASSWORD}")
                .doesNotContain(connId);
            String fixed = yaml.replace("public.missing", "public.items");
            as(admin).contentType("application/yaml").body(fixed.getBytes(StandardCharsets.UTF_8)).queryParam("dryRun", true).post("/import").then().statusCode(200)
                .body("dryRun", equalTo(true))
                .body("items.find { it.name == 'pg' }.action", equalTo("UNCHANGED"))
                .body("items.find { it.name == 'items_sync' }.action", equalTo("UNCHANGED"))
                .body("items.find { it.name == 'broken' }.action", equalTo("UPDATE"));
            as(admin).get("/jobs/" + jobId).then().body("config.source.object", equalTo("public.missing"));
            as(admin).contentType("application/yaml").body(fixed.replace("name: broken", "name: fixed").getBytes(StandardCharsets.UTF_8)).post("/import").then().statusCode(200)
                .body("items.find { it.name == 'fixed' }.action", equalTo("CREATE"));
            as(admin).get("/jobs").then().body("find { it.name == 'fixed' }.config.source.object", equalTo("public.items"));
            as(admin).contentType("application/yaml").body("jobs:\n  - name: orphan\n    source: { connection: nowhere, object: x }\n".getBytes(StandardCharsets.UTF_8))
                .post("/import").then().statusCode(400).body("message", equalTo("Job orphan: unknown connection nowhere"));

            // retention (14 days, set above) prunes older finished runs but keeps each job's latest
            Instant old = Instant.now().minus(Duration.ofDays(20));
            db.update("INSERT INTO run (id, job_id, kind, state, started_at, finished_at) VALUES ('old-run', ?, 'POLL', 'FAILED', ?, ?)",
                jobId, old, old);
            assertThat(housekeeping.prune()).isEqualTo(1);
            as(admin).get("/jobs/" + jobId + "/runs").then().body("size()", equalTo(1));
        } finally {
            channel.stop(0);
        }
    }

    /**
     * Design 6: the run queues its changes in the outbox and succeeds; the webhook sink, down, dead-letters the batch and
     * pauses. A retry delivers the dead letter, and once resumed the sink gets new changes again.
     */
    private static void deadLettersAreRetriedOnceTheWebhookIsBack(String admin, String dbConnId) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        String hookId = as(admin).body(Map.of("name", "hook", "kind", "webhook", "role", "SINK",
                "url", "http://127.0.0.1:" + port + "/hook", "authType", "HMAC", "password", "hook-secret"))
            .post("/connections").then().statusCode(201).body("authType", equalTo("HMAC")).extract().path("id");
        as(admin).post("/connections/" + hookId + "/test").then().statusCode(200).body("ok", equalTo(false));
        as(admin).get("/connections/" + hookId + "/objects").then().statusCode(400);

        Map<String, Object> config = Map.of(
            "name", "items_hook",
            "source", Map.of("connection", dbConnId, "object", "public.items"),
            "sinks", List.of(Map.of("type", "webhook", "connection", hookId, "retry", Map.of("maxAttempts", 1))));
        as(admin).body(Map.of("config", Map.of("name", "bad", "source", config.get("source"),
                "sinks", List.of(Map.of("type", "webhook", "connection", dbConnId)))))
            .post("/jobs").then().statusCode(400).body("message", equalTo("A webhook sink needs a webhook connection"));
        String jobId = as(admin).body(Map.of("config", config)).post("/jobs").then().statusCode(201).extract().path("id");
        as(admin).post("/jobs/" + jobId + "/run").then().statusCode(202);
        awaitRunFinished(admin, jobId);
        awaitSink(admin, jobId, "PAUSED");
        String sinkId = as(admin).get("/jobs/" + jobId).then().body("status", equalTo("ACTIVE")).body("lastRun.state", equalTo("SUCCEEDED"))
            .body("sinks[0].label", equalTo("hook")).body("sinks[0].lastError", containsString("dead letters"))
            .extract().path("sinks[0].id");
        as(admin).get("/overview").then().body("stoppedSinks", equalTo(1));
        String deadLetterId = as(admin).queryParam("jobId", jobId).get("/dead-letters").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].changeCount", equalTo(2)).body("[0].jobName", equalTo("items_hook"))
            .extract().path("[0].id");
        as(admin).get("/dead-letters/" + deadLetterId).then().statusCode(200).body("payload.changes.size()", equalTo(2));
        as(admin).get("/overview").then().body("openDeadLetters", equalTo(1));

        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer hook = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        hook.createContext("/hook", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        hook.start();
        try {
            as(admin).post("/connections/" + hookId + "/test").then().body("ok", equalTo(true));
            as(admin).body(Map.of("ids", List.of(deadLetterId))).post("/dead-letters/retry").then().statusCode(200)
                .body("resolved", equalTo(1)).body("failed.size()", equalTo(0));
            assertThat(received).singleElement().asString().contains("\"job\":\"items_hook\"").contains("\"op\":\"upsert\"");
            as(admin).queryParam("jobId", jobId).get("/dead-letters").then().body("size()", equalTo(0));
            as(admin).queryParam("status", "RESOLVED").get("/dead-letters").then().body("[0].resolution", equalTo("RETRIED"));
            as(admin).queryParam("sink", sinkId).post("/jobs/" + jobId + "/resume-sink").then().statusCode(200)
                .body("sinks[0].status", equalTo("ACTIVE"));
            as(admin).get("/audit").then().body("action", hasItem("dead-letter.retry")).body("action", hasItem("sink.resume"));

            sql("INSERT INTO items VALUES (3, 'c')");
            as(admin).post("/jobs/" + jobId + "/run").then().statusCode(202);
            awaitRunFinished(admin, jobId);
            Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
            while (received.size() < 2 && Instant.now().isBefore(deadline)) Thread.sleep(200);
            assertThat(received).hasSize(2).last().asString().contains("\"id\":3");
            as(admin).get("/jobs/" + jobId).then().body("sinks[0].backlog", equalTo(0)).body("sinks[0].deliveredAt", notNullValue());

            // a resnapshot sends that sink every row again
            as(admin).queryParam("sink", sinkId).post("/jobs/" + jobId + "/resnapshot").then().statusCode(202);
            awaitRunFinished(admin, jobId);
            deadline = Instant.now().plus(Duration.ofSeconds(15));
            while (received.size() < 3 && Instant.now().isBefore(deadline)) Thread.sleep(200);
            assertThat(received).hasSize(3).last().asString().contains("\"id\":1").contains("\"id\":2").contains("\"id\":3");
            as(admin).get("/audit").then().body("action", hasItem("sink.resnapshot"));
            as(admin).delete("/jobs/" + jobId).then().statusCode(204);
            as(admin).queryParam("status", "ALL").queryParam("jobId", jobId).get("/dead-letters").then().body("size()", equalTo(0));
        } finally {
            hook.stop(0);
        }
    }

    private static void awaitSink(String session, String jobId, String status) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            if (status.equals(as(session).get("/jobs/" + jobId).then().extract().path("sinks[0].status"))) return;
            Thread.sleep(200);
        }
        throw new AssertionError("sink did not become " + status);
    }

    private static String login(String username, String password, boolean mustChange) {
        return RestAssured.given().basePath(API).contentType(ContentType.JSON).body(Map.of("username", username, "password", password))
            .post("/auth/login").then().statusCode(200).body("mustChangePassword", equalTo(mustChange))
            .extract().cookie("datasync_session");
    }

    private static RequestSpecification as(String session) {
        return RestAssured.given().basePath(API).contentType(ContentType.JSON).cookie("datasync_session", session);
    }

    private static void awaitRunFinished(String session, String jobId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            Boolean running = as(session).get("/jobs/" + jobId).then().extract().path("running");
            if (!running) return;
            Thread.sleep(200);
        }
        throw new AssertionError("run did not finish");
    }

    private static void sql(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    private static String query(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
