package io.datasync.core;

import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Embedded SQLite state: run history and the per-job hash index. One connection, synchronized,
 * so every state write goes through a single writer.
 */
public final class StateStore implements AutoCloseable {

    /** A row of the hash index. Hashes are hex strings; {@code keyJson} uses source column names. */
    record IndexEntry(String keyHash, String keyJson, String rowHash) {}

    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_BINDS = 500;

    private final Connection conn;

    public StateStore(Path file) throws IOException, SQLException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        String url = "jdbc:sqlite:" + file;
        Flyway.configure().dataSource(url, null, null).load().migrate();
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.setBusyTimeout(5_000);
        conn = cfg.createConnection(url);
    }

    public synchronized String startRun(String jobId, String kind) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO run (id, job_id, kind, started_at, state) VALUES (?, ?, ?, ?, 'RUNNING')")) {
            ps.setString(1, id);
            ps.setString(2, jobId);
            ps.setString(3, kind);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
        return id;
    }

    public synchronized void finishRun(String runId, RunState state, RunStats stats, String error) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE run SET finished_at = ?, state = ?, rows_read = ?, upserts = ?, deletes = ?, unchanged = ?, error = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, state.name());
            ps.setLong(3, stats.rowsRead());
            ps.setLong(4, stats.upserts());
            ps.setLong(5, stats.deletes());
            ps.setLong(6, stats.unchanged());
            ps.setString(7, error);
            ps.setString(8, runId);
            ps.executeUpdate();
        }
    }

    /** Known row hashes for the given key hashes. */
    synchronized Map<String, String> rowHashes(String jobId, Collection<String> keyHashes) throws SQLException {
        Map<String, String> found = new HashMap<>();
        List<String> all = List.copyOf(keyHashes);
        for (int from = 0; from < all.size(); from += MAX_BINDS) {
            List<String> part = all.subList(from, Math.min(from + MAX_BINDS, all.size()));
            String sql = "SELECT key_hash, row_hash FROM row_index WHERE job_id = ? AND key_hash IN ("
                + String.join(", ", Collections.nCopies(part.size(), "?")) + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, jobId);
                for (int i = 0; i < part.size(); i++) ps.setBytes(i + 2, HEX.parseHex(part.get(i)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) found.put(HEX.formatHex(rs.getBytes(1)), HEX.formatHex(rs.getBytes(2)));
                }
            }
        }
        return found;
    }

    /** Records the current hash of every key seen in this run, in one transaction. */
    synchronized void markSeen(String jobId, String runId, List<IndexEntry> entries) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO row_index (job_id, key_hash, key_json, row_hash, last_seen_run) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (job_id, key_hash) DO UPDATE SET row_hash = excluded.row_hash, last_seen_run = excluded.last_seen_run""")) {
                for (IndexEntry e : entries) {
                    ps.setString(1, jobId);
                    ps.setBytes(2, HEX.parseHex(e.keyHash()));
                    ps.setString(3, e.keyJson());
                    ps.setBytes(4, HEX.parseHex(e.rowHash()));
                    ps.setString(5, runId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    synchronized long indexSize(String jobId) throws SQLException {
        return count("SELECT COUNT(*) FROM row_index WHERE job_id = ?", jobId);
    }

    /** Keys not seen in the given run, i.e. deleted from the source. */
    synchronized long countUnseen(String jobId, String runId) throws SQLException {
        return count("SELECT COUNT(*) FROM row_index WHERE job_id = ? AND last_seen_run <> ?", jobId, runId);
    }

    synchronized List<IndexEntry> unseen(String jobId, String runId, int limit) throws SQLException {
        List<IndexEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT key_hash, key_json, row_hash FROM row_index WHERE job_id = ? AND last_seen_run <> ? LIMIT ?")) {
            ps.setString(1, jobId);
            ps.setString(2, runId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) entries.add(new IndexEntry(HEX.formatHex(rs.getBytes(1)), rs.getString(2), HEX.formatHex(rs.getBytes(3))));
            }
        }
        return entries;
    }

    synchronized void remove(String jobId, List<IndexEntry> entries) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM row_index WHERE job_id = ? AND key_hash = ?")) {
                for (IndexEntry e : entries) {
                    ps.setString(1, jobId);
                    ps.setBytes(2, HEX.parseHex(e.keyHash()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }

    private long count(String sql, String... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    /**
     * Runs {@code work} in one transaction, serialized with every other access to the store.
     * A nested call joins the transaction already open.
     */
    public synchronized <T> T transact(SqlFunction<T> work) throws SQLException {
        if (!conn.getAutoCommit()) return work.apply(conn);
        conn.setAutoCommit(false);
        try {
            T result = work.apply(conn);
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private interface SqlWork {
        void run() throws SQLException;
    }

    private void inTransaction(SqlWork work) throws SQLException {
        transact(c -> {
            work.run();
            return null;
        });
    }
}
