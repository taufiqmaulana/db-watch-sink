package io.datasync.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

/**
 * The job outbox and each sink's offset in it (design 6). Runs append batches addressed to some of the job's sinks;
 * every sink delivers the batches addressed to it in order and acknowledges them. Batches every sink has passed are
 * trimmed. A sink more than the job's {@code outbox.maxBatches} behind is flagged RESNAPSHOT_REQUIRED and skips to
 * the end; a resnapshot gives it a full load again.
 */
public final class Outbox {

    public enum SinkStatus { ACTIVE, PAUSED, RESNAPSHOT_REQUIRED }

    /** A sink's position: {@code attempts} failed at the next batch, retried from {@code nextRetryAt}. */
    public record SinkState(String sinkId, SinkStatus status, long lastAcked, int attempts, Instant nextRetryAt, String lastError) {}

    record Entry(long batchId, String payload) {}

    private final StateStore state;

    public Outbox(StateStore state) {
        this.state = state;
    }

    /**
     * Drops the offsets of sinks the job no longer has and starts new ones at the end of the outbox, so a sink
     * only receives batches written after it was added (it gets a full load first). Returns every sink's state.
     */
    Map<String, SinkState> prepare(String jobId, Collection<String> sinkIds) throws SQLException {
        return state.transact(c -> {
            Map<String, SinkState> states = new LinkedHashMap<>();
            for (SinkState s : states(c, jobId)) {
                if (sinkIds.contains(s.sinkId())) states.put(s.sinkId(), s);
                else update(c, "DELETE FROM sink_offset WHERE job_id = ? AND sink_id = ?", jobId, s.sinkId());
            }
            long last = lastBatchId(c, jobId);
            for (String sinkId : sinkIds) {
                if (states.containsKey(sinkId)) continue;
                update(c, "INSERT INTO sink_offset (job_id, sink_id, last_acked_batch, status) VALUES (?, ?, ?, 'ACTIVE')", jobId, sinkId, last);
                states.put(sinkId, new SinkState(sinkId, SinkStatus.ACTIVE, last, 0, null, null));
            }
            trim(c, jobId);
            return states;
        });
    }

    /** Appends a batch for {@code recipients}; {@code payload} gets the new batch id. Joins the caller's transaction. */
    long append(String jobId, Collection<String> recipients, int changeCount, LongFunction<String> payload) throws SQLException {
        return state.transact(c -> {
            long id = lastBatchId(c, jobId) + 1;
            update(c, "INSERT INTO outbox_batch (job_id, batch_id, recipients, change_count, payload, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                jobId, id, recipients.stream().map(r -> "|" + r).collect(Collectors.joining()) + "|", changeCount, payload.apply(id), Instant.now());
            return id;
        });
    }

    /** The next batches for the sink, oldest first. */
    List<Entry> pending(String jobId, String sinkId, int limit) throws SQLException {
        return state.transact(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = prepare(c, "SELECT b.batch_id, b.payload FROM outbox_batch b JOIN sink_offset o"
                    + " ON o.job_id = b.job_id AND o.sink_id = ? WHERE b.job_id = ? AND b.batch_id > o.last_acked_batch"
                    + " AND instr(b.recipients, ?) > 0 ORDER BY b.batch_id LIMIT ?", sinkId, jobId, "|" + sinkId + "|", limit);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Entry(rs.getLong(1), rs.getString(2)));
            }
            return out;
        });
    }

    /** Records the batch as delivered; false when the sink was stopped or moved on meanwhile, so delivery should stop. */
    boolean ack(String jobId, String sinkId, long batchId) throws SQLException {
        return state.transact(c -> {
            int n = update(c, "UPDATE sink_offset SET last_acked_batch = ?, attempts = 0, next_retry_at = NULL, last_error = NULL,"
                + " delivered_at = ? WHERE job_id = ? AND sink_id = ? AND status = 'ACTIVE' AND last_acked_batch < ?",
                batchId, Instant.now(), jobId, sinkId, batchId);
            trim(c, jobId);
            return n > 0;
        });
    }

    /** A failed attempt at the next batch, to be retried at {@code retryAt}. */
    void failed(String jobId, String sinkId, int attempts, Instant retryAt, String error) throws SQLException {
        state.transact(c -> update(c, "UPDATE sink_offset SET attempts = ?, next_retry_at = ?, last_error = ? WHERE job_id = ? AND sink_id = ?",
            attempts, retryAt, error, jobId, sinkId));
    }

    /** Stops delivery to the sink until it is resumed (onDeadLetter: PAUSE). */
    void pause(String jobId, String sinkId, String error) throws SQLException {
        state.transact(c -> update(c, "UPDATE sink_offset SET status = 'PAUSED', attempts = 0, next_retry_at = NULL, last_error = ?"
            + " WHERE job_id = ? AND sink_id = ?", error, jobId, sinkId));
    }

    /** Continues a paused sink from where it stopped. */
    public void resume(String jobId, String sinkId) throws SQLException {
        state.transact(c -> update(c, "UPDATE sink_offset SET status = 'ACTIVE', attempts = 0, next_retry_at = NULL, last_error = NULL"
            + " WHERE job_id = ? AND sink_id = ? AND status = 'PAUSED'", jobId, sinkId));
    }

    /** Drops the sink's backlog and makes the next run send it every row (a full load), as for a new sink. */
    public void resnapshot(String jobId, String sinkId) throws SQLException {
        state.transact(c -> {
            update(c, "UPDATE sink_offset SET status = 'ACTIVE', last_acked_batch = ?, attempts = 0, next_retry_at = NULL, last_error = NULL"
                + " WHERE job_id = ? AND sink_id = ?", lastBatchId(c, jobId), jobId, sinkId);
            update(c, "DELETE FROM sink_load WHERE job_id = ? AND sink_id = ?", jobId, sinkId);
            trim(c, jobId);
            return null;
        });
    }

    /**
     * Flags sinks with more than {@code maxBatches} batches waiting as RESNAPSHOT_REQUIRED and moves them to the end of
     * the outbox, so it can be trimmed. Returns the sinks flagged now.
     */
    List<String> flagLagging(String jobId, int maxBatches) throws SQLException {
        return state.transact(c -> {
            List<String> flagged = new ArrayList<>();
            for (SinkState s : states(c, jobId)) {
                if (s.status() == SinkStatus.RESNAPSHOT_REQUIRED || backlog(c, jobId, s) <= maxBatches) continue;
                update(c, "UPDATE sink_offset SET status = 'RESNAPSHOT_REQUIRED', last_acked_batch = ?, attempts = 0, next_retry_at = NULL,"
                    + " last_error = ? WHERE job_id = ? AND sink_id = ?", lastBatchId(c, jobId),
                    "fell more than " + maxBatches + " batches behind; resnapshot it to load it again", jobId, s.sinkId());
                update(c, "DELETE FROM sink_load WHERE job_id = ? AND sink_id = ?", jobId, s.sinkId());
                flagged.add(s.sinkId());
            }
            trim(c, jobId);
            return flagged;
        });
    }

    public SinkState sinkState(String jobId, String sinkId) throws SQLException {
        return state.transact(c -> states(c, jobId).stream().filter(s -> s.sinkId().equals(sinkId)).findFirst().orElse(null));
    }

    /** Batches waiting for the sink. */
    public long backlog(String jobId, String sinkId) throws SQLException {
        return state.transact(c -> {
            SinkState s = states(c, jobId).stream().filter(x -> x.sinkId().equals(sinkId)).findFirst().orElse(null);
            return s == null ? 0 : backlog(c, jobId, s);
        });
    }

    private static long backlog(Connection c, String jobId, SinkState s) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT count(*) FROM outbox_batch WHERE job_id = ? AND batch_id > ? AND instr(recipients, ?) > 0",
                jobId, s.lastAcked(), "|" + s.sinkId() + "|");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<SinkState> states(Connection c, String jobId) throws SQLException {
        List<SinkState> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, "SELECT sink_id, status, last_acked_batch, attempts, next_retry_at, last_error"
                + " FROM sink_offset WHERE job_id = ? ORDER BY sink_id", jobId);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp retry = rs.getTimestamp(5);
                out.add(new SinkState(rs.getString(1), SinkStatus.valueOf(rs.getString(2)), rs.getLong(3), rs.getInt(4),
                    retry == null ? null : retry.toInstant(), rs.getString(6)));
            }
        }
        return out;
    }

    /** The highest batch id used, also counting offsets, so ids never repeat after the outbox is trimmed empty. */
    private static long lastBatchId(Connection c, String jobId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT max(coalesce((SELECT max(batch_id) FROM outbox_batch WHERE job_id = ?), 0),"
                + " coalesce((SELECT max(last_acked_batch) FROM sink_offset WHERE job_id = ?), 0))", jobId, jobId);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Deletes batches every sink of the job has passed (all of them once the job has no sinks). */
    private static void trim(Connection c, String jobId) throws SQLException {
        update(c, "DELETE FROM outbox_batch WHERE job_id = ? AND batch_id <= coalesce((SELECT min(last_acked_batch) FROM sink_offset"
            + " WHERE job_id = ?), (SELECT max(batch_id) FROM outbox_batch WHERE job_id = ?))", jobId, jobId, jobId);
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, args)) {
            return ps.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i] instanceof Instant t ? Timestamp.from(t) : args[i]);
        }
        return ps;
    }
}
