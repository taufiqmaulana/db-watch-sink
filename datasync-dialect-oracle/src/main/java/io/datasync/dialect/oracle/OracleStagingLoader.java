package io.datasync.dialect.oracle;

import io.datasync.api.ObjectRef;
import io.datasync.jdbc.BulkLoader;
import io.datasync.jdbc.SqlDialect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stages rows in a global temporary table with an array-bound INSERT. Oracle has no per-transaction
 * temp tables and its DDL commits, so the table is created once by {@link #prepare} and only filled
 * here; ON COMMIT DELETE ROWS empties it after each batch.
 */
final class OracleStagingLoader implements BulkLoader {

    private static final int NAME_EXISTS = 955;

    private final SqlDialect dialect;

    OracleStagingLoader(SqlDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public void prepare(Connection c, ObjectRef target, List<String> columns) throws SQLException {
        String name = stagingName(target, columns);
        boolean exists;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM user_tables WHERE table_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next() && rs.getInt(1) > 0;
            }
        }
        if (exists) return;
        try (Statement st = c.createStatement()) {
            st.execute("CREATE GLOBAL TEMPORARY TABLE " + dialect.quote(name) + " ON COMMIT DELETE ROWS AS SELECT "
                + columnList(columns) + " FROM " + dialect.qualify(target) + " WHERE 1 = 0");
        } catch (SQLException e) {
            if (e.getErrorCode() != NAME_EXISTS) throw e;   // created concurrently by another sink
        }
    }

    @Override
    public String stage(Connection c, ObjectRef target, List<String> columns, List<List<Object>> rows) throws SQLException {
        String staging = dialect.quote(stagingName(target, columns));
        try (Statement st = c.createStatement()) {
            // upserts and deletes share the table when their columns are the same
            st.executeUpdate("DELETE FROM " + staging);
        }
        String insert = "INSERT INTO " + staging + " (" + columnList(columns) + ") VALUES ("
            + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
        try (PreparedStatement ps = c.prepareStatement(insert)) {
            for (List<Object> row : rows) {
                for (int i = 0; i < row.size(); i++) ps.setObject(i + 1, bindValue(row.get(i)));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return staging;
    }

    /** Booleans as 'Y'/'N' (CHAR(1)); UUIDs as text, which the driver cannot bind directly. */
    static Object bindValue(Object v) {
        return switch (v) {
            case Boolean b -> b ? "Y" : "N";
            case UUID u -> u.toString();
            case null, default -> v;
        };
    }

    /** DS_STG_ plus a hash of target and columns: stable across restarts and within 30 characters (11g limit). */
    static String stagingName(ObjectRef target, List<String> columns) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((target + "|" + String.join(",", columns)).getBytes(StandardCharsets.UTF_8));
            return "DS_STG_" + HexFormat.of().withUpperCase().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String columnList(List<String> columns) {
        return columns.stream().map(dialect::quote).collect(Collectors.joining(", "));
    }
}
