package io.datasync.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end through the CLI: PostgreSQL to a webhook, with deletes sent as flagged upserts and a dead letter. */
@Testcontainers
class WebhookSyncTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private HttpServer hook;
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private Path config;

    @BeforeEach
    void setUp() throws Exception {
        sql("DROP TABLE IF EXISTS events", "CREATE TABLE events (id int PRIMARY KEY, title text)",
            "INSERT INTO events SELECT g, 'e' || g FROM generate_series(1, 3) g");
        hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/hook", exchange -> {
            if (status.get() < 300) received.add(JSON.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        hook.start();
        config = dir.resolve("datasync.yaml");
        Files.writeString(config, """
            connections:
              - { name: pg, kind: jdbc-postgres, url: "%s", username: %s, password: %s }
            jobs:
              - name: events_hook
                source: { connection: pg, object: public.events }
                maxDeletePercent: 100
                sinks:
                  - type: webhook
                    url: http://127.0.0.1:%d/hook
                    auth: { type: hmac, secret: s3cret }
                    batch: { mode: per-row }
                    deleteAs: upsert-with-flag
                    retry: { maxAttempts: 2, initialBackoff: 1s }
                    onDeadLetter: SKIP
            """.formatted(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), hook.getAddress().getPort()));
    }

    @AfterEach
    void tearDown() {
        hook.stop(0);
    }

    @Test
    void postsChangesAndFlagsDeletes() throws Exception {
        assertThat(run()).isZero();
        assertThat(received).hasSize(3).allSatisfy(body -> {
            assertThat(body.get("job").asText()).isEqualTo("events_hook");
            assertThat(body.get("changes")).hasSize(1);
        });

        received.clear();
        sql("DELETE FROM events WHERE id = 2");
        assertThat(run()).isZero();
        JsonNode change = received.getFirst().at("/changes/0");
        assertThat(change.get("op").asText()).isEqualTo("upsert");
        assertThat(change.at("/row/title").asText()).as("the last row seen").isEqualTo("e2");
        assertThat(change.at("/row/_is_deleted").asBoolean()).isTrue();

        // with onDeadLetter: SKIP a refused batch is set aside and the run still succeeds
        received.clear();
        status.set(422);
        sql("UPDATE events SET title = 'changed' WHERE id = 1");
        assertThat(run()).isZero();
        assertThat(received).isEmpty();
        status.set(200);
        assertThat(run()).as("nothing changed since: the dead letter holds the update").isZero();
        assertThat(received).isEmpty();
    }

    private int run() throws Exception {
        return Main.run(new String[] {"run", config.toString(), "--state", dir.resolve("state.db").toString()});
    }

    private static void sql(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }
}
