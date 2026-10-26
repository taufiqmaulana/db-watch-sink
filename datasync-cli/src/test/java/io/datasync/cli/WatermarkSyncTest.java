package io.datasync.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** HYBRID end to end (design 5.1, 5.4): watermark polls read only changed rows; a reconcile catches the rest. */
@Testcontainers
class WatermarkSyncTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    @TempDir
    Path dir;

    @Test
    void pollsReadChangedRowsAndReconcileCatchesMissedUpdatesAndDeletes() throws Exception {
        sql("CREATE TABLE items (id int PRIMARY KEY, label text, updated_at timestamp)",
            "INSERT INTO items SELECT g, 'v1', now() - interval '1 hour' FROM generate_series(1, 5) g");
        Path config = dir.resolve("datasync.yaml");
        Files.writeString(config, """
            connections:
              - { name: pg, kind: jdbc-postgres, url: "%s", username: %s, password: %s }
            jobs:
              - name: items_sync
                source: { connection: pg, object: public.items }
                mode: HYBRID
                watermark: { column: updated_at, overlap: 1m }
                reconcile: { cron: "0 2 * * *" }
                maxDeletePercent: 50
                batchSize: 2
                sinks:
                  - { type: jdbc, connection: pg, target: public.items_copy }
            """.formatted(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        String state = dir.resolve("state.db").toString();
        String[] poll = {"run", config.toString(), "--state", state};
        String[] reconcile = {"run", config.toString(), "--state", state, "--reconcile"};

        assertThat(Main.run(poll)).isZero();
        assertThat(query("SELECT count(*) FROM items_copy")).as("no cursor yet: full read").isEqualTo("5");
        // move the cursor past the hour-old rows, so later polls read only what is bumped from here on
        sql("UPDATE items SET updated_at = now() WHERE id = 1");
        assertThat(Main.run(poll)).isZero();

        sql("UPDATE items SET label = 'v2', updated_at = now() WHERE id = 2",
            "UPDATE items SET label = 'v2' WHERE id = 3",          // watermark not bumped: missed by polls
            "DELETE FROM items WHERE id = 4");
        assertThat(Main.run(poll)).isZero();
        assertThat(labels()).isEqualTo("1:v1,2:v2,3:v1,4:v1,5:v1");
        assertThat(query("SELECT count(*) FROM items_copy WHERE _is_deleted")).as("polls never delete").isEqualTo("0");

        assertThat(Main.run(reconcile)).isZero();
        assertThat(labels()).isEqualTo("1:v1,2:v2,3:v2,4:v1,5:v1");
        assertThat(query("SELECT string_agg(id::text, ',' ORDER BY id) FROM items_copy WHERE _is_deleted")).isEqualTo("4");

        sql("UPDATE items SET label = 'v3', updated_at = now() WHERE id = 5");
        assertThat(Main.run(poll)).isZero();
        assertThat(labels()).isEqualTo("1:v1,2:v2,3:v2,4:v1,5:v3");
    }

    private static String labels() throws SQLException {
        return query("SELECT string_agg(id || ':' || label, ',' ORDER BY id) FROM items_copy");
    }

    private static void sql(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    private static String query(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
