package io.datasync.core;

import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.ReadSpec;
import io.datasync.api.RecordCursor;
import io.datasync.api.WatermarkSpec;
import io.datasync.core.StateStore.IndexEntry;

import java.sql.SQLException;
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
    private long rowsRead, upserts, deletes, unchanged, seq, batchId;

    DiffRun(StateStore state, String runId) {
        this.state = state;
        this.runId = runId;
    }

    RunStats stats() {
        return new RunStats(rowsRead, upserts, deletes, unchanged);
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
                .map(e -> new Change(Change.Op.DELETE, job.mapping().key(Canonical.parse(e.keyJson())), null, null,
                    idempotencyKey(job, e.keyJson(), "del"), now, ++seq))
                .toList();
            deliver(job, removed, removed);
            state.remove(job.id(), entries);
            deletes += entries.size();
        }
    }

    private void diff(Job job, List<Map<String, Object>> rows) throws SQLException {
        ColumnMapping mapping = job.mapping();
        List<IndexEntry> entries = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            for (String k : mapping.sourceKeys()) {
                if (row.get(k) == null) throw new IllegalStateException("key column " + k + " is null in " + job.readSpec().object());
            }
            if (job.watermark() != null) highestWatermark = Watermark.max(highestWatermark, row.get(job.watermark().column()));
            String keyJson = Canonical.json(row, mapping.sourceKeys());
            entries.add(new IndexEntry(Canonical.hash(keyJson), keyJson, Canonical.hash(Canonical.values(row, mapping.sourceColumns()))));
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
    private void deliver(Job job, List<Change> changed, List<Change> all) {
        if (changed.isEmpty() && all.isEmpty()) return;
        batchId++;
        job.sinks().forEach((sinkId, sink) -> {
            List<Change> batch = loadedSinks.contains(sinkId) ? changed : all;
            if (!batch.isEmpty()) sink.apply(new ChangeBatch(job.id(), sinkId, batchId, job.mapping().target(), batch));
        });
    }

    private static String idempotencyKey(Job job, String keyJson, String rowHashOrDel) {
        return Canonical.sha256Hex(job.id() + "|" + keyJson + "|" + rowHashOrDel);
    }
}
