package io.datasync.server;

import io.datasync.core.StateStore;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Thin SQL helpers over the shared {@link StateStore}, so API writes go through the same single writer as runs. */
@Singleton
public class Db {

    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final StateStore store;

    public Db(@ConfigProperty(name = "datasync.data-dir") String dataDir) {
        try {
            store = new StateStore(Path.of(dataDir, "datasync.db"));
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("cannot open the state store in " + dataDir, e);
        }
    }

    public StateStore store() {
        return store;
    }

    public <T> T tx(StateStore.SqlFunction<T> work) {
        try {
            return store.transact(work);
        } catch (SQLException e) {
            throw new IllegalStateException("state store error", e);
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return tx(c -> query(c, sql, mapper, args));
    }

    public <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        return query(sql, mapper, args).stream().findFirst();
    }

    public int update(String sql, Object... args) {
        return tx(c -> update(c, sql, args));
    }

    public static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(mapper.map(rs));
            return out;
        }
    }

    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, args)) {
            return ps.executeUpdate();
        }
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            ps.setObject(i + 1, a instanceof Instant t ? Timestamp.from(t) : a instanceof Enum<?> e ? e.name() : a);
        }
        return ps;
    }

    @PreDestroy
    void close() throws SQLException {
        store.close();
    }
}
