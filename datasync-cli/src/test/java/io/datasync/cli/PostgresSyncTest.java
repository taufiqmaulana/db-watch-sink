package io.datasync.cli;

import org.junit.jupiter.api.BeforeEach;
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

/** End to end through the CLI: PostgreSQL source schema to PostgreSQL target schema in one container. */
@Testcontainers
class PostgresSyncTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    @TempDir
    Path dir;

    private Path config;

    @BeforeEach
    void setUp() throws Exception {
        sql("DROP SCHEMA IF EXISTS src CASCADE",
            "DROP TABLE IF EXISTS public.customers CASCADE",
            "CREATE SCHEMA src",
            "CREATE TABLE src.customers (customer_id int PRIMARY KEY, cust_name varchar(50), balance numeric(12,2),"
                + " updated_at timestamp, photo bytea)",
            "INSERT INTO src.customers SELECT g, 'c' || g, 10.5, now(), '\\x01' FROM generate_series(1, 5) g");
        config = dir.resolve("datasync.yaml");
        Files.writeString(config, """
            connections:
              - { name: pg_src, kind: jdbc-postgres, url: "%1$s", username: %2$s, password: %3$s }
              - { name: pg_dw,  kind: jdbc-postgres, url: "%1$s", username: %2$s, password: %3$s }
            jobs:
              - name: customers_sync
                source: { connection: pg_src, object: src.customers }
                columns: { include: "*", rename: { cust_name: customer_name } }
                maxDeletePercent: 40
                batchSize: 2
                sinks:
                  - { type: jdbc, connection: pg_dw, target: public.customers }
            """.formatted(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    @Test
    void syncsInsertsUpdatesSoftDeletesAndResurrections() throws Exception {
        assertThat(run()).isZero();
        assertThat(query("SELECT count(*) FROM public.customers WHERE NOT _is_deleted")).isEqualTo("5");
        assertThat(query("SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM information_schema.columns"
            + " WHERE table_name = 'customers' AND table_schema = 'public'"))
            .isEqualTo("customer_id,customer_name,balance,updated_at,_deleted_at,_is_deleted");

        sql("UPDATE src.customers SET cust_name = 'renamed' WHERE customer_id = 2",
            "DELETE FROM src.customers WHERE customer_id = 5",
            "INSERT INTO src.customers VALUES (6, 'new', 1, now(), null)");
        assertThat(run()).isZero();
        assertThat(query("SELECT customer_name FROM public.customers WHERE customer_id = 2")).isEqualTo("renamed");
        assertThat(query("SELECT _is_deleted AND _deleted_at IS NOT NULL FROM public.customers WHERE customer_id = 5")).isEqualTo("t");
        assertThat(query("SELECT string_agg(customer_id::text, ',' ORDER BY customer_id) FROM public.customers_active")).isEqualTo("1,2,3,4,6");

        sql("INSERT INTO src.customers VALUES (5, 'back', 2, now(), null)");
        assertThat(run()).isZero();
        assertThat(query("SELECT customer_name || ' ' || _is_deleted || ' ' || coalesce(_deleted_at::text, 'null')"
            + " FROM public.customers WHERE customer_id = 5")).isEqualTo("back false null");

        sql("DELETE FROM src.customers WHERE customer_id <= 3");
        assertThat(run()).as("delete guard: 3 of 6 is over 40%").isEqualTo(2);
        assertThat(query("SELECT count(*) FROM public.customers WHERE NOT _is_deleted")).isEqualTo("6");
        assertThat(run("--allow-deletes")).isZero();
        assertThat(query("SELECT count(*) FROM public.customers_active")).isEqualTo("3");
    }

    private int run(String... extra) throws Exception {
        String[] args = {"run", config.toString(), "--state", dir.resolve("state.db").toString()};
        String[] all = new String[args.length + extra.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(extra, 0, all, args.length, extra.length);
        return Main.run(all);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static void sql(String... statements) throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    private static String query(String sql) throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
