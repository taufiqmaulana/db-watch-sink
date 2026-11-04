package io.datasync.core;

import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.ObjectSchema;
import io.datasync.api.Sink;
import io.datasync.api.SinkException;
import io.datasync.core.Outbox.SinkState;
import io.datasync.core.Outbox.SinkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Delivers one sink's outbox batches in order (design 6), each in pieces of the sink's batch limit. A failed piece is
 * retried with backoff per the sink's {@link DeliveryPolicy}: either waiting in place ({@code waitForRetries}, the CLI)
 * or by recording when to try again and stopping (the server retries later). When retries run out the piece goes to
 * the dead letters; with onDeadLetter: SKIP delivery carries on, with PAUSE the rest of the batch goes with it and the
 * sink is paused until resumed.
 */
final class SinkDelivery {

    private static final Logger log = LoggerFactory.getLogger(SinkDelivery.class);
    /** Batches loaded at a time: one, since a batch can hold batchSize rows of JSON. */
    private static final int PAGE = 1;

    private final StateStore state;
    private final Outbox outbox;
    private final String jobId;
    private final String sinkId;
    private final DeliveryPolicy policy;
    /** Opens and initialises the sink for batches of the given target schema. */
    private final Function<ObjectSchema, Sink> opener;

    private int batches, attempts;
    private long changes, failedAttempts, deadLetters;
    private String error;
    private Instant retryAt;

    SinkDelivery(StateStore state, String jobId, String sinkId, DeliveryPolicy policy, Function<ObjectSchema, Sink> opener) {
        this.state = state;
        this.outbox = new Outbox(state);
        this.jobId = jobId;
        this.sinkId = sinkId;
        this.policy = policy;
        this.opener = opener;
    }

    DeliveryResult run(boolean waitForRetries) throws SQLException {
        SinkState start = outbox.sinkState(jobId, sinkId);
        if (start == null) return result();
        boolean due = start.nextRetryAt() == null || waitForRetries || !start.nextRetryAt().isAfter(Instant.now());
        if (start.status() == SinkStatus.ACTIVE && due) {
            attempts = start.attempts();
            deliver(waitForRetries);
        }
        DeliveryResult result = result();
        if (result.batches() > 0 || result.failedAttempts() > 0) {
            log.info("job {} sink {}: delivered {} batches ({} changes), {} failed attempts, {} dead letters, {} waiting{}", jobId, sinkId,
                result.batches(), result.changes(), result.failedAttempts(), result.deadLetters(), result.backlog(),
                result.error() == null ? "" : ": " + result.error());
        }
        return result;
    }

    private void deliver(boolean waitForRetries) throws SQLException {
        Sink sink = null;
        ObjectSchema schema = null;
        try {
            for (List<Outbox.Entry> page; !(page = outbox.pending(jobId, sinkId, PAGE)).isEmpty(); ) {
                for (Outbox.Entry entry : page) {
                    ChangeBatch stored = Batches.decode(entry.payload());
                    if (sink == null || !stored.schema().equals(schema)) {
                        if (sink != null) sink.close();
                        sink = null;
                        sink = opener.apply(stored.schema());
                        schema = stored.schema();
                    }
                    ChangeBatch batch = new ChangeBatch(jobId, sinkId, entry.batchId(), stored.schema(), stored.changes());
                    if (!send(sink, batch, waitForRetries)) return;
                }
            }
        } catch (RuntimeException e) {
            // the sink could not be opened: count it as a failed attempt at the next batch
            failedAttempts++;
            attempts++;
            error = Errors.describe(e);
            retryAt = Instant.now().plus(policy.backoff(attempts, null));
            outbox.failed(jobId, sinkId, attempts, retryAt, error);
        } finally {
            if (sink != null) sink.close();
        }
    }

    /** Delivers one batch; false when delivery should stop (waiting to retry, paused, or the sink moved on). */
    private boolean send(Sink sink, ChangeBatch batch, boolean waitForRetries) throws SQLException {
        int limit = sink.capabilities().batchLimit();
        int size = limit > 0 ? limit : Math.max(1, batch.changes().size());
        for (int from = 0; from < batch.changes().size(); ) {
            int to = Math.min(from + size, batch.changes().size());
            try {
                sink.apply(piece(batch, from, to));
                from = to;
                continue;
            } catch (RuntimeException e) {
                failedAttempts++;
                attempts++;
                error = Errors.describe(e);
                SinkException failure = e instanceof SinkException se ? se : null;
                if ((failure == null || failure.retryable()) && attempts < policy.maxAttempts()) {
                    Duration wait = policy.backoff(attempts, failure == null ? null : failure.retryAfter());
                    if (waitForRetries) {
                        pause(wait);
                        continue;
                    }
                    retryAt = Instant.now().plus(wait);
                    outbox.failed(jobId, sinkId, attempts, retryAt, error);
                    return false;
                }
            }
            if (policy.onDeadLetter() == DeliveryPolicy.OnDeadLetter.SKIP) {
                deadLetter(piece(batch, from, to));
                attempts = 0;
                from = to;
                continue;
            }
            deadLetter(piece(batch, from, batch.changes().size()));
            outbox.ack(jobId, sinkId, batch.batchId());
            outbox.pause(jobId, sinkId, "paused after a batch was moved to the dead letters: " + error);
            return false;
        }
        attempts = 0;
        error = null;
        retryAt = null;
        batches++;
        changes += batch.changes().size();
        return outbox.ack(jobId, sinkId, batch.batchId());
    }

    private void deadLetter(ChangeBatch piece) throws SQLException {
        String id = state.addDeadLetter(jobId, sinkId, piece.batchId(), piece.changes().size(), Batches.encode(piece), error, attempts);
        deadLetters++;
        log.warn("job {} sink {}: {} changes moved to dead letter {} after {} attempts: {}", jobId, sinkId, piece.changes().size(), id,
            attempts, error);
    }

    /** Applies a dead letter's changes again, in pieces of the sink's batch limit, without retries. */
    static void apply(Sink sink, ChangeBatch batch) {
        int limit = sink.capabilities().batchLimit();
        int size = limit > 0 ? limit : Math.max(1, batch.changes().size());
        for (int from = 0; from < batch.changes().size(); from += size) {
            sink.apply(piece(batch, from, Math.min(from + size, batch.changes().size())));
        }
    }

    private static ChangeBatch piece(ChangeBatch batch, int from, int to) {
        List<Change> part = from == 0 && to == batch.changes().size() ? batch.changes() : batch.changes().subList(from, to);
        return new ChangeBatch(batch.jobId(), batch.sinkId(), batch.batchId(), batch.schema(), part);
    }

    private DeliveryResult result() throws SQLException {
        SinkState now = outbox.sinkState(jobId, sinkId);
        return new DeliveryResult(jobId, sinkId, batches, changes, failedAttempts, deadLetters,
            now == null ? null : now.status(), now == null ? error : now.lastError(), now == null ? null : now.nextRetryAt(),
            outbox.backlog(jobId, sinkId));
    }

    private static void pause(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", e);
        }
    }
}
