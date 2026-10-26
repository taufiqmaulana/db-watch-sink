package io.datasync.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** The M1 outcome: an Oracle view synced to PostgreSQL with SNAPSHOT_DIFF, deletes included. */
@Testcontainers
class OracleToPostgresSyncTest {

    @Container
    static final OracleContainer ORA = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    @TempDir
    Path dir;

    @Test
    void syncsAViewIncludingTypesAndDeletes() throws Exception {
        ora("CREATE TABLE CUSTOMERS (CUSTOMER_ID NUMBER(10) PRIMARY KEY, CUST_NAME VARCHAR2(50), BALANCE NUMBER,"
                + " CREATED DATE, UPDATED_AT TIMESTAMP(6), NOTE CLOB)",
            "CREATE VIEW CUSTOMERS_V AS SELECT * FROM CUSTOMERS",
            "INSERT INTO CUSTOMERS SELECT LEVEL, 'c' || LEVEL, LEVEL * 1.25, DATE '2026-01-02' + LEVEL,"
                + " TIMESTAMP '2026-01-02 03:04:05.123456', 'note ' || LEVEL FROM dual CONNECT BY LEVEL <= 5");
        Path config = dir.resolve("datasync.yaml");
        Files.writeString(config, """
            connections:
              - { name: oracle_prod, kind: jdbc-oracle, url: "%s", username: %s, password: %s }
              - { name: pg_dw, kind: jdbc-postgres, url: "%s", username: %s, password: %s }
            jobs:
              - name: customers_sync
                source: { connection: oracle_prod, object: %s.CUSTOMERS_V }
                keys: [CUSTOMER_ID]
                columns: { include: "*", rename: { CUST_NAME: customer_name } }
                batchSize: 2
                sinks:
                  - { type: jdbc, connection: pg_dw, target: public.customers }
              - name: customers_wm
                source: { connection: oracle_prod, object: %s.CUSTOMERS }
                mode: WATERMARK
                watermark: { column: UPDATED_AT, overlap: 1m }
                batchSize: 2
                sinks:
                  - { type: jdbc, connection: pg_dw, target: public.customers_wm }
            """.formatted(ORA.getJdbcUrl(), ORA.getUsername(), ORA.getPassword(),
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), ORA.getUsername().toUpperCase(), ORA.getUsername().toUpperCase()));
        String[] args = {"run", config.toString(), "--state", dir.resolve("state.db").toString()};

        assertThat(Main.run(args)).isZero();
        assertThat(pg("SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY ordinal_position)"
            + " FROM information_schema.columns WHERE table_name = 'customers'"))
            .isEqualTo("customer_id:bigint,customer_name:text,balance:numeric,created:timestamp without time zone,"
                + "updated_at:timestamp without time zone,note:text,"
                + "_deleted_at:timestamp with time zone,_is_deleted:boolean");
        assertThat(pg("SELECT customer_name || '|' || balance || '|' || created || '|' || updated_at || '|' || note"
            + " FROM customers WHERE customer_id = 3"))
            .isEqualTo("c3|3.75|2026-01-05 00:00:00|2026-01-02 03:04:05.123456|note 3");

        ora("UPDATE CUSTOMERS SET BALANCE = 0 WHERE CUSTOMER_ID = 1", "DELETE FROM CUSTOMERS WHERE CUSTOMER_ID = 4",
            "UPDATE CUSTOMERS SET CUST_NAME = 'bumped', UPDATED_AT = SYSTIMESTAMP WHERE CUSTOMER_ID = 2");
        assertThat(Main.run(args)).isZero();
        assertThat(pg("SELECT balance FROM customers WHERE customer_id = 1")).isEqualTo("0");
        assertThat(pg("SELECT string_agg(customer_id::text, ',' ORDER BY customer_id) FROM customers_active")).isEqualTo("1,2,3,5");
        assertThat(pg("SELECT cust_name FROM customers_wm WHERE customer_id = 2")).isEqualTo("bumped");
        assertThat(pg("SELECT string_agg(customer_id::text, ',' ORDER BY customer_id) FROM customers_wm_active"))
            .as("watermark jobs do not delete").isEqualTo("1,2,3,4,5");

        // the cursor is now past the old rows: a change without a bumped watermark reaches the snapshot job only
        ora("UPDATE CUSTOMERS SET BALANCE = 99 WHERE CUSTOMER_ID = 3");
        assertThat(Main.run(args)).isZero();
        assertThat(pg("SELECT balance FROM customers WHERE customer_id = 3")).isEqualTo("99");
        assertThat(pg("SELECT balance FROM customers_wm WHERE customer_id = 3")).isEqualTo("3.75");
    }

    private static void ora(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(ORA.getJdbcUrl(), ORA.getUsername(), ORA.getPassword());
             Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    private static String pg(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
