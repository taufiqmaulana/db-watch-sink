package io.datasync.core;

import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.Column;
import io.datasync.api.ReadSpec;
import io.datasync.api.RecordCursor;
import io.datasync.api.Sink;
import io.datasync.api.SinkException;
import io.datasync.api.WatermarkSpec;
import io.datasync.core.StateStore.IndexEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One run: rows read from the source are diffed against the hash index, applied to every sink, and only then
 * recorded in the index (the single-pass MVP variant, design 6). A crash in between re-sends the chunk on the
 * next run, which idempotent sinks absorb.
 * <p>
 * A full scan (design 5.2: SNAPSHOT_DIFF, or a RECONCILE) also turns keys missing from the source into deletes,
 * behind the delete guard. A watermark read (design 5.1: a POLL of a WATERMARK or HYBRID job) reads only rows at
 * or after the cursor minus the overlap window, never deletes, and then moves the cursor to the highest value
 * delivered. Without a cursor yet, it reads everything in key order instead.
 * <p>
 * The index is per job, so it only says what changed since the last run. A sink without a completed full load
 * (just added, or pointed at a new target) therefore gets every row of a full read; a watermark job with such a
 * sink does a full read.
 */
final class DiffRun {

    private final StateStore state;
    private final String runId;
    private Set<String> loadedSinks;
    private Object highestWatermark;
    private static final Logger log = LoggerFactory.getLogger(DiffRun.class);

    private long rowsRead, upserts, deletes, unchanged, seq, batchId, deadLetters;

    DiffRun(StateStore state, String runId) {
        this.state = state;
        this.runId = runId;
    }

    RunStats stats() {
        return new RunStats(rowsRead, upserts, deletes, unchanged);
    }

    /** Batches this run moved to the dead letters. */
    long deadLetters() {
        return deadLetters;
    }

    void run(Job job, RunKind kind, boolean allowDeletes) throws SQLException {
        long indexed = state.indexSize(job.id());
        loadedSinks = state.loadedSinks(job.id(), job.sinks().keySet());
        Watermark watermark = job.watermark();
        boolean fullScan = watermark == null || kind == RunKind.RECONCILE;
        ReadSpec spec = job.readSpec();
        if (!fullScan && loadedSinks.containsAll(job.sinks().keySet())) {
            Optional<String> cursor = state.cursor(job.id(), watermark.column());
            if (cursor.isPresent()) spec = watermarkRead(job, watermark.from(cursor.get()));
        }

        int chunkSize = spec.chunkSize();
        try (RecordCursor cursor = job.source().read(spec)) {
            List<Map<String, Object>> chunk = new ArrayList<>(chunkSize);
            while (cursor.hasNext()) {
                chunk.add(cursor.next());
                if (chunk.size() == chunkSize) {
                    diff(job, chunk);
                    chunk.clear();
                }
            }
            if (!chunk.isEmpty()) diff(job, chunk);
        }
        // every sink now holds every source row read; deletes below are sent to all of them
        state.markLoaded(job.id(), job.sinks().keySet());
        loadedSinks = job.sinks().keySet();

        if (fullScan) applyDeletes(job, indexed, allowDeletes);
        if (watermark != null && highestWatermark != null) {
            state.saveCursor(job.id(), watermark.column(), Watermark.format(highestWatermark));
        }
    }

    /** Rows at or after {@code from} in watermark order, resuming by (watermark, key). */
    private static ReadSpec watermarkRead(Job job, Object from) {
        ReadSpec full = job.readSpec();
        String column = job.watermark().column();
        List<String> order = Stream.concat(Stream.of(column), full.keyColumns().stream().filter(k -> !k.equals(column))).toList();
        return new ReadSpec(full.object(), full.columns(), order, full.fetchSize(), full.chunkSize(), full.queryTimeout(),
            new WatermarkSpec(column, from));
    }

    private void applyDeletes(Job job, long indexed, boolean allowDeletes) throws SQLException {
        long gone = state.countUnseen(job.id(), runId);
        if (!allowDeletes) checkDeleteGuard(job, indexed, gone);
        for (List<IndexEntry> entries; !(entries = state.unseen(job.id(), runId, job.readSpec().chunkSize())).isEmpty(); ) {
            Instant now = Instant.now();
            List<Change> removed = entries.stream()
                .map(e -> new Change(Change.Op.DELETE, job.mapping().key(Canonical.parse(e.keyJson())),
                    e.rowJson() == null ? null : Canonical.parse(e.rowJson()), null,
                    idempotencyKey(job, e.keyJson(), "del"), now, ++seq))
                .toList();
            deliver(job, removed, removed);
            state.remove(job.id(), entries);
            deletes += entries.size();
        }
    }

    private void diff(Job job, List<Map<String, Object>> read) throws SQLException {
        ColumnMapping mapping = job.mapping();
        List<Map<String, Object>> rows = new ArrayList<>(read.size());
        List<IndexEntry> entries = new ArrayList<>(read.size());
        for (Map<String, Object> raw : read) {
            // the cursor keeps the source's own type, since the next read binds it against the source column
            if (job.watermark() != null) highestWatermark = Watermark.max(highestWatermark, raw.get(job.watermark().column()));
            Map<String, Object> row = mapping.normalize(raw);
            rows.add(row);
            for (String k : mapping.sourceKeys()) {
                if (row.get(k) == null) throw new IllegalStateException("key column " + k + " is null in " + job.readSpec().object());
            }
            String keyJson = Canonical.json(row, mapping.sourceKeys());
            // the last row delivered, under target names, for sinks that send it with deletes
            String rowJson = job.keepRows() ? Canonical.json(mapping.row(row), targetColumns(job)) : null;
            entries.add(new IndexEntry(Canonical.hash(keyJson), keyJson, Canonical.hash(Canonical.values(row, mapping.sourceColumns())), rowJson));
        }

        Map<String, String> known = state.rowHashes(job.id(), entries.stream().map(IndexEntry::keyHash).toList());
        boolean fullLoad = !loadedSinks.containsAll(job.sinks().keySet());
        Instant now = Instant.now();
        List<Change> changed = new ArrayList<>();
        List<Change> all = new ArrayList<>(fullLoad ? rows.size() : 0);
        for (int i = 0; i < rows.size(); i++) {
            IndexEntry e = entries.get(i);
            boolean isChanged = !e.rowHash().equals(known.get(e.keyHash()));
            if (!isChanged && !fullLoad) continue;
            Change change = new Change(Change.Op.UPSERT, mapping.key(rows.get(i)), mapping.row(rows.get(i)), e.rowHash(),
                idempotencyKey(job, e.keyJson(), e.rowHash()), now, ++seq);
            if (isChanged) changed.add(change);
            if (fullLoad) all.add(change);
        }
        deliver(job, changed, all);
        state.markSeen(job.id(), runId, entries);

        rowsRead += rows.size();
        upserts += changed.size();
        unchanged += rows.size() - changed.size();
    }

    private static List<String> targetColumns(Job job) {
        return job.mapping().target().columns().stream().map(Column::name).toList();
    }

    private void checkDeleteGuard(Job job, long indexed, long gone) {
        if (indexed > 0 && rowsRead == 0) {
            throw new DeleteGuardException("source returned no rows but the index holds " + indexed + " keys");
        }
        if (gone * 100 > indexed * job.maxDeletePercent()) {
            throw new DeleteGuardException(gone + " of " + indexed + " keys would be deleted, over the "
                + job.maxDeletePercent() + "% limit");
        }
    }

    /** {@code changed} goes to fully loaded sinks, {@code all} to sinks still waiting for their full load. */
    private void deliver(Job job, List<Change> changed, List<Change> all) throws SQLException {
        if (changed.isEmpty() && all.isEmpty()) return;
        batchId++;
        for (Map.Entry<String, Sink> sink : job.sinks().entrySet()) {
            List<Change> batch = loadedSinks.contains(sink.getKey()) ? changed : all;
            if (!batch.isEmpty()) send(job, sink.getKey(), sink.getValue(), batch);
        }
    }

    /**
     * Applies changes to one sink in pieces of its batch limit. A failed piece is retried with backoff per the
     * sink's policy, then moved to the dead letters (design 6); with onDeadLetter: PAUSE that stops the run.
     */
    private void send(Job job, String sinkId, Sink sink, List<Change> changes) throws SQLException {
        int limit = sink.capabilities().batchLimit();
        int size = limit > 0 ? limit : changes.size();
        DeliveryPolicy policy = job.policy(sinkId);
        for (int from = 0; from < changes.size(); from += size) {
            ChangeBatch batch = new ChangeBatch(job.id(), sinkId, batchId, job.mapping().target(),
                changes.subList(from, Math.min(from + size, changes.size())));
            for (int attempt = 1; ; attempt++) {
                try {
                    sink.apply(batch);
                    break;
                } catch (RuntimeException e) {
                    SinkException failure = e instanceof SinkException se ? se : null;
                    if ((failure == null || failure.retryable()) && attempt < policy.maxAttempts()) {
                        pause(policy.backoff(attempt, failure == null ? null : failure.retryAfter()));
                        continue;
                    }
                    deadLetter(batch, e, attempt, policy);
                    break;
                }
            }
        }
    }

    private void deadLetter(ChangeBatch batch, RuntimeException error, int attempts, DeliveryPolicy policy) throws SQLException {
        String message = Errors.describe(error);
        String id = state.addDeadLetter(batch.jobId(), batch.sinkId(), batch.batchId(), batch.changes().size(), Batches.encode(batch),
            message, attempts);
        deadLetters++;
        log.warn("job {} sink {}: {} changes moved to dead letter {} after {} attempts: {}", batch.jobId(), batch.sinkId(),
            batch.changes().size(), id, attempts, message);
        if (policy.onDeadLetter() == DeliveryPolicy.OnDeadLetter.PAUSE) {
            throw new DeadLetterPause("sink " + batch.sinkId() + " could not take a batch, moved to the dead letters: " + message, error);
        }
    }

    private static void pause(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", e);
        }
    }

    private static String idempotencyKey(Job job, String keyJson, String rowHashOrDel) {
        return Canonical.sha256Hex(job.id() + "|" + keyJson + "|" + rowHashOrDel);
    }
}
