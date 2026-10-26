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

/** Oracle as a sink (M5): every canonical type, Oracle's '' = NULL, a text key, soft deletes and resurrection. */
@Testcontainers
class PostgresToOracleSyncTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    @Container
    static final OracleContainer ORA = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

    @TempDir
    Path dir;

    @Test
    void syncsAllTypesAndDeletesIntoOracle() throws Exception {
        pg("CREATE TABLE items (id int PRIMARY KEY, code varchar(20) NOT NULL, note text NOT NULL, amount numeric(12,2),"
                + " ratio double precision, active boolean, born date, seen timestamp, seen_tz timestamptz, uid uuid,"
                + " payload jsonb, data bytea)",
            "INSERT INTO items SELECT g, 'C' || g, CASE WHEN g = 1 THEN '' ELSE repeat('n', 5000) END, g * 1.5, g / 3.0,"
                + " g % 2 = 0, DATE '2026-01-01' + g, TIMESTAMP '2026-01-02 03:04:05.123456', TIMESTAMPTZ '2026-01-02 03:04:05+07',"
                + " gen_random_uuid(), '{\"a\": 1}', '\\x0102' FROM generate_series(1, 5) g",
            "CREATE TABLE tags (name text PRIMARY KEY, label text)",
            "INSERT INTO tags VALUES ('red', 'Red'), ('blue', 'Blue')");
        String schema = ORA.getUsername().toUpperCase();
        Path config = dir.resolve("datasync.yaml");
        Files.writeString(config, """
            connections:
              - { name: pg, kind: jdbc-postgres, url: "%s", username: %s, password: %s }
              - { name: ora, kind: jdbc-oracle, url: "%s", username: %s, password: %s }
            jobs:
              - name: items_sync
                source: { connection: pg, object: public.items }
                columns: { include: ["*", "data"] }
                maxDeletePercent: 50
                batchSize: 2
                sinks:
                  - { type: jdbc, connection: ora, target: %s.items }
              - name: tags_sync
                source: { connection: pg, object: public.tags }
                maxDeletePercent: 50
                sinks:
                  - { type: jdbc, connection: ora, target: %s.tags, deleteMode: HARD }
            """.formatted(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(),
                ORA.getJdbcUrl(), ORA.getUsername(), ORA.getPassword(), schema, schema));
        String[] args = {"run", config.toString(), "--state", dir.resolve("state.db").toString()};

        assertThat(Main.run(args)).isZero();
        assertThat(ora("SELECT LISTAGG(column_name || ':' || data_type, ',') WITHIN GROUP (ORDER BY column_id)"
            + " FROM user_tab_columns WHERE table_name = 'ITEMS'"))
            .isEqualTo("ID:NUMBER,CODE:VARCHAR2,NOTE:CLOB,AMOUNT:NUMBER,RATIO:BINARY_DOUBLE,ACTIVE:CHAR,BORN:DATE,"
                + "SEEN:TIMESTAMP(6),SEEN_TZ:TIMESTAMP(6) WITH TIME ZONE,UID:VARCHAR2,PAYLOAD:CLOB,DATA:BLOB,"
                + "_DELETED_AT:TIMESTAMP(6) WITH TIME ZONE,_IS_DELETED:CHAR");
        assertThat(ora("SELECT code || '|' || NVL(TO_CHAR(DBMS_LOB.GETLENGTH(note)), 'null') || '|' || amount || '|' || active"
            + " || '|' || TO_CHAR(seen, 'YYYY-MM-DD HH24:MI:SS.FF6') || '|' || RAWTOHEX(DBMS_LOB.SUBSTR(data, 16, 1)) || '|' || \"_IS_DELETED\""
            + " FROM items WHERE id = 2")).isEqualTo("C2|5000|3|Y|2026-01-02 03:04:05.123456|0102|N");
        assertThat(ora("SELECT NVL(TO_CHAR(DBMS_LOB.GETLENGTH(note)), 'null') FROM items WHERE id = 1"))
            .as("Oracle stores '' as NULL, so the column was created nullable").isEqualTo("null");
        assertThat(ora("SELECT LISTAGG(name, ',') WITHIN GROUP (ORDER BY name) FROM tags")).isEqualTo("blue,red");

        pg("UPDATE items SET amount = 0 WHERE id = 3", "DELETE FROM items WHERE id = 4", "DELETE FROM tags WHERE name = 'red'");
        assertThat(Main.run(args)).isZero();
        assertThat(ora("SELECT amount FROM items WHERE id = 3")).isEqualTo("0");
        assertThat(ora("SELECT \"_IS_DELETED\" || NVL2(\"_DELETED_AT\", ' set', ' null') FROM items WHERE id = 4")).isEqualTo("Y set");
        assertThat(ora("SELECT LISTAGG(id, ',') WITHIN GROUP (ORDER BY id) FROM items_active")).isEqualTo("1,2,3,5");
        assertThat(ora("SELECT LISTAGG(name, ',') WITHIN GROUP (ORDER BY name) FROM tags")).isEqualTo("blue");

        pg("INSERT INTO items (id, code, note) VALUES (4, 'back', 'x')");
        assertThat(Main.run(args)).isZero();
        assertThat(ora("SELECT code || ' ' || \"_IS_DELETED\" || NVL2(\"_DELETED_AT\", ' set', ' null') FROM items WHERE id = 4"))
            .isEqualTo("back N null");
    }

    private static void pg(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }

    private static String ora(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(ORA.getJdbcUrl(), ORA.getUsername(), ORA.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
