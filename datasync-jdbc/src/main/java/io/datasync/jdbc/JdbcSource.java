package io.datasync.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import io.datasync.api.CanonicalType;
import io.datasync.api.Column;
import io.datasync.api.ObjectRef;
import io.datasync.api.ObjectSchema;
import io.datasync.api.ReadSpec;
import io.datasync.api.RecordCursor;
import io.datasync.api.SourceConnector;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

final class JdbcSource implements SourceConnector {

    private final String kind;
    private final SqlDialect dialect;
    private final HikariDataSource pool;

    JdbcSource(String kind, SqlDialect dialect, HikariDataSource pool) {
        this.kind = kind;
        this.dialect = dialect;
        this.pool = pool;
    }

    @Override
    public String type() {
        return kind;
    }

    @Override
    public ObjectSchema describe(ObjectRef ref) {
        List<Column> columns = new ArrayList<>();
        try (Connection c = pool.getConnection();
             ResultSet rs = c.getMetaData().getColumns(null, ref.schema(), ref.name(), null)) {
            while (rs.next()) {
                // the name argument is a LIKE pattern, so '_' can match other objects
                if (!ref.name().equals(rs.getString("TABLE_NAME"))) continue;
                int digits = rs.getInt("DECIMAL_DIGITS");
                JdbcColumn col = new JdbcColumn(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME"),
                    rs.getInt("COLUMN_SIZE"), rs.wasNull() ? null : digits,
                    rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                columns.add(new Column(col.name(), dialect.toCanonical(col), col.nullable()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("describe " + ref + " failed", e);
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("object not found: " + ref);
        return new ObjectSchema(ref, columns, List.copyOf(primaryKeyOf(ref)));
    }

    @Override
    public List<ObjectRef> listObjects(String schemaFilter) {
        List<ObjectRef> objects = new ArrayList<>();
        try (Connection c = pool.getConnection();
             ResultSet rs = c.getMetaData().getTables(null, schemaFilter, "%", new String[] {"TABLE", "VIEW"})) {
            while (rs.next()) objects.add(new ObjectRef(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME")));
        } catch (SQLException e) {
            throw new IllegalStateException("list objects failed", e);
        }
        return objects;
    }

    @Override
    public Set<String> primaryKeyOf(ObjectRef ref) {
        Map<Short, String> bySeq = new TreeMap<>();
        try (Connection c = pool.getConnection();
             ResultSet rs = c.getMetaData().getPrimaryKeys(null, ref.schema(), ref.name())) {
            while (rs.next()) bySeq.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        } catch (SQLException e) {
            throw new IllegalStateException("primary key lookup for " + ref + " failed", e);
        }
        return new LinkedHashSet<>(bySeq.values());
    }

    @Override
    public RecordCursor read(ReadSpec spec) {
        Map<String, CanonicalType> types = new LinkedHashMap<>();
        describe(spec.object()).columns().forEach(col -> types.put(col.name(), col.type()));
        try {
            return new KeysetCursor(pool.getConnection(), spec, spec.columns().stream().map(types::get).toList());
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open source connection", e);
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    /** Streams the scan as a series of short chunk queries, each resuming after the last key seen. */
    private final class KeysetCursor implements RecordCursor {
        private final Connection conn;
        private final ReadSpec spec;
        private final List<CanonicalType> types;
        private Iterator<Map<String, Object>> chunk = Collections.emptyIterator();
        private List<Object> lastKey;
        private boolean exhausted;

        KeysetCursor(Connection conn, ReadSpec spec, List<CanonicalType> types) {
            this.conn = conn;
            this.spec = spec;
            this.types = types;
        }

        @Override
        public boolean hasNext() {
            while (!chunk.hasNext() && !exhausted) chunk = fetchChunk().iterator();
            return chunk.hasNext();
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) throw new NoSuchElementException();
            return chunk.next();
        }

        private List<Map<String, Object>> fetchChunk() {
            List<Map<String, Object>> rows = new ArrayList<>(spec.chunkSize());
            try (PreparedStatement ps = conn.prepareStatement(dialect.keysetQuery(spec, lastKey != null))) {
                ps.setFetchSize(spec.fetchSize());
                ps.setQueryTimeout((int) spec.queryTimeout().toSeconds());
                if (lastKey != null) {
                    List<Object> binds = SqlDialect.keysetBinds(lastKey);
                    for (int i = 0; i < binds.size(); i++) ps.setObject(i + 1, binds.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < types.size(); i++) row.put(spec.columns().get(i), JdbcValues.read(rs, i + 1, types.get(i)));
                        rows.add(row);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("read " + spec.object() + " failed", e);
            }
            exhausted = rows.size() < spec.chunkSize();
            if (!rows.isEmpty()) lastKey = spec.keyColumns().stream().map(rows.getLast()::get).toList();
            return rows;
        }

        @Override
        public void close() {
            try {
                conn.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
