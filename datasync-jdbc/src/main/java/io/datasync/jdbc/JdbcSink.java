package io.datasync.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import io.datasync.api.ApplyResult;
import io.datasync.api.CanonicalType;
import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.Column;
import io.datasync.api.HealthStatus;
import io.datasync.api.ObjectRef;
import io.datasync.api.ObjectSchema;
import io.datasync.api.Sink;
import io.datasync.api.SinkCapabilities;
import io.datasync.api.SinkContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads each batch into staging tables, then upserts and deletes from them in one target transaction.
 * Deletes are soft by default: {@code _deleted_at} and {@code _is_deleted} are set together, and a later
 * upsert of the same key clears both.
 */
final class JdbcSink implements Sink {

    enum DeleteMode { SOFT, HARD, IGNORE }

    private final String kind;
    private final SqlDialect dialect;
    private final HikariDataSource pool;

    private ObjectRef target;
    private DeleteMode deleteMode;
    private String deletedAt;
    private String isDeleted;
    private List<String> fields;      // column names of incoming rows
    private List<String> keyFields;
    private List<String> writeCols;   // target columns written by an upsert, soft-delete flags included
    private List<String> keyCols;

    JdbcSink(String kind, SqlDialect dialect, HikariDataSource pool) {
        this.kind = kind;
        this.dialect = dialect;
        this.pool = pool;
    }

    @Override
    public String type() {
        return kind;
    }

    @Override
    public void init(SinkContext ctx, ObjectSchema schema) {
        Map<String, Object> cfg = ctx.config();
        Object targetName = cfg.get("target");
        if (targetName == null) throw new IllegalArgumentException("sink " + ctx.sinkId() + ": missing 'target'");
        ObjectRef t = ObjectRef.parse(targetName.toString());
        target = new ObjectRef(t.schema() == null ? null : dialect.normalizeIdentifier(t.schema()), dialect.normalizeIdentifier(t.name()));
        deleteMode = DeleteMode.valueOf(cfg.getOrDefault("deleteMode", "SOFT").toString().toUpperCase());
        deletedAt = dialect.normalizeIdentifier(cfg.getOrDefault("softDeleteColumn", "_deleted_at").toString());
        isDeleted = dialect.normalizeIdentifier(cfg.getOrDefault("softDeleteFlagColumn", "_is_deleted").toString());

        fields = schema.columns().stream().map(Column::name).toList();
        keyFields = schema.keyColumns();
        keyCols = keyFields.stream().map(dialect::normalizeIdentifier).toList();
        Stream<String> dataCols = fields.stream().map(dialect::normalizeIdentifier);
        writeCols = (deleteMode == DeleteMode.SOFT ? Stream.concat(dataCols, Stream.of(deletedAt, isDeleted)) : dataCols).toList();

        try (Connection c = pool.getConnection()) {
            Set<String> existing = targetColumns(c);
            if (existing.isEmpty()) {
                if (!Boolean.parseBoolean(cfg.getOrDefault("autoCreate", "true").toString())) {
                    throw new IllegalStateException("target " + target + " does not exist and autoCreate is off");
                }
                create(c, schema.columns());
                existing = targetColumns(c);
            }
            Set<String> found = existing;
            List<String> missing = writeCols.stream().filter(col -> !found.contains(col)).toList();
            if (!missing.isEmpty()) throw new IllegalStateException("target " + target + " is missing columns " + missing);
            BulkLoader loader = dialect.bulkLoader();
            loader.prepare(c, target, writeCols);
            if (deleteMode != DeleteMode.IGNORE) loader.prepare(c, target, keyCols);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("init of sink " + ctx.sinkId() + " failed", e);
        }
    }

    @Override
    public ApplyResult apply(ChangeBatch batch) {
        List<List<Object>> upserts = new ArrayList<>();
        List<List<Object>> deletes = new ArrayList<>();
        for (Change ch : batch.changes()) {
            if (ch.op() == Change.Op.UPSERT) {
                List<Object> values = new ArrayList<>(writeCols.size());
                fields.forEach(f -> values.add(ch.row().get(f)));
                if (deleteMode == DeleteMode.SOFT) {
                    values.add(null);
                    values.add(false);
                }
                upserts.add(values);
            } else if (deleteMode != DeleteMode.IGNORE) {
                deletes.add(keyFields.stream().map(ch.key()::get).toList());
            }
        }
        // the pool is not auto-commit, so on failure the pool rolls the transaction back on close
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            BulkLoader loader = dialect.bulkLoader();
            if (!upserts.isEmpty()) {
                st.executeUpdate(dialect.upsertSql(target, loader.stage(c, target, writeCols, upserts), writeCols, keyCols));
            }
            if (!deletes.isEmpty()) {
                st.executeUpdate(deleteSql(loader.stage(c, target, keyCols, deletes)));
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("apply of batch " + batch.batchId() + " to " + target + " failed", e);
        }
        return new ApplyResult(upserts.size(), deletes.size());
    }

    @Override
    public SinkCapabilities capabilities() {
        return new SinkCapabilities(true, false, true, 0);
    }

    @Override
    public HealthStatus health() {
        try (Connection c = pool.getConnection()) {
            return new HealthStatus(c.isValid(5), null);
        } catch (SQLException e) {
            return new HealthStatus(false, e.getMessage());
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    private String deleteSql(String staging) {
        String match = "EXISTS (SELECT 1 FROM " + staging + " s WHERE "
            + keyCols.stream().map(dialect::quote).map(k -> "t." + k + " = s." + k).collect(Collectors.joining(" AND ")) + ")";
        if (deleteMode == DeleteMode.HARD) return "DELETE FROM " + dialect.qualify(target) + " t WHERE " + match;
        return "UPDATE " + dialect.qualify(target) + " t SET "
            + dialect.quote(deletedAt) + " = " + dialect.currentTimeSql() + ", "
            + dialect.quote(isDeleted) + " = " + dialect.booleanLiteral(true)
            + " WHERE t." + dialect.quote(isDeleted) + " = " + dialect.booleanLiteral(false) + " AND " + match;
    }

    private Set<String> targetColumns(Connection c) throws SQLException {
        Set<String> cols = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, target.schema(), target.name(), null)) {
            while (rs.next()) {
                if (target.name().equals(rs.getString("TABLE_NAME"))) cols.add(rs.getString("COLUMN_NAME"));
            }
        }
        return cols;
    }

    private void create(Connection c, List<Column> columns) throws SQLException {
        List<String> defs = new ArrayList<>();
        for (Column col : columns) {
            String name = dialect.normalizeIdentifier(col.name());
            boolean key = keyCols.contains(name);
            boolean text = col.type().kind() == CanonicalType.Kind.STRING || col.type().kind() == CanonicalType.Kind.JSON;
            // where '' is stored as NULL, a NOT NULL text column would reject empty strings from the source
            boolean notNull = key || !col.nullable() && !(text && dialect.emptyStringIsNull());
            defs.add(dialect.quote(name) + " " + (key ? dialect.nativeKeyType(col.type()) : dialect.nativeType(col.type()))
                + (notNull ? " NOT NULL" : ""));
        }
        if (deleteMode == DeleteMode.SOFT) {
            String flag = dialect.quote(isDeleted);
            defs.add(dialect.quote(deletedAt) + " " + dialect.nativeType(CanonicalType.of(CanonicalType.Kind.TIMESTAMP_TZ)));
            defs.add(flag + " " + dialect.nativeType(CanonicalType.of(CanonicalType.Kind.BOOLEAN))
                + " DEFAULT " + dialect.booleanLiteral(false) + " NOT NULL"
                + " CHECK (" + flag + " IN (" + dialect.booleanLiteral(true) + ", " + dialect.booleanLiteral(false) + "))");
        }
        defs.add("PRIMARY KEY (" + keyCols.stream().map(dialect::quote).collect(Collectors.joining(", ")) + ")");

        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE " + dialect.qualify(target) + " (" + String.join(", ", defs) + ")");
            if (deleteMode == DeleteMode.SOFT) {
                ObjectRef view = new ObjectRef(target.schema(), dialect.normalizeIdentifier(target.name() + "_active"));
                st.executeUpdate("CREATE OR REPLACE VIEW " + dialect.qualify(view) + " AS SELECT * FROM " + dialect.qualify(target)
                    + " WHERE " + dialect.quote(isDeleted) + " = " + dialect.booleanLiteral(false));
            }
        }
        c.commit();
    }
}
