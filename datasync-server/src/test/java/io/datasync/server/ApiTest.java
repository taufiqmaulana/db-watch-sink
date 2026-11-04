package io.datasync.server;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
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
    }

    /** Design 6: a webhook that is down dead-letters the batch and pauses the job; a retry delivers it later. */
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
        as(admin).get("/jobs/" + jobId).then().body("status", equalTo("PAUSED")).body("lastRun.state", equalTo("FAILED"));
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
            as(admin).get("/audit").then().body("action", hasItem("dead-letter.retry")).body("action", hasItem("job.pause"));

            as(admin).post("/jobs/" + jobId + "/resume").then().statusCode(200);
            as(admin).post("/jobs/" + jobId + "/run").then().statusCode(202);
            awaitRunFinished(admin, jobId);
            as(admin).get("/jobs/" + jobId).then().body("lastRun.state", equalTo("SUCCEEDED"));
            as(admin).delete("/jobs/" + jobId).then().statusCode(204);
            as(admin).queryParam("status", "ALL").queryParam("jobId", jobId).get("/dead-letters").then().body("size()", equalTo(0));
        } finally {
            hook.stop(0);
        }
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
