package io.datasync.core;

import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.Column;
import io.datasync.api.ReadSpec;
import io.datasync.api.RecordCursor;
import io.datasync.api.WatermarkSpec;
import io.datasync.core.Outbox.SinkStatus;
import io.datasync.core.StateStore.IndexEntry;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One run's read side (design 6): rows read from the source are diffed against the hash index, and each chunk's
 * changes are written to the job's outbox in the same transaction that records them in the index. Sinks then
 * deliver from the outbox on their own (see {@link SinkDelivery}), so a slow or failing sink holds up no other.
 * <p>
 * A full scan (design 5.2: SNAPSHOT_DIFF, or a RECONCILE) also turns keys missing from the source into deletes,
 * behind the delete guard. A watermark read (design 5.1: a POLL of a WATERMARK or HYBRID job) reads only rows at
 * or after the cursor minus the overlap window, never deletes, and then moves the cursor to the highest value
 * delivered. Without a cursor yet, it reads everything in key order instead.
 * <p>
 * The index is per job, so it only says what changed since the last run. A sink without a completed full load
 * (just added, pointed at a new target, or resnapshotted) therefore gets every row of a full read; a watermark job
 * with such a sink does a full read. Sinks flagged RESNAPSHOT_REQUIRED get nothing until they are resnapshotted.
 */
final class DiffRun {

    private final StateStore state;
    private final Outbox outbox;
    private final String runId;
    /** The sinks this run writes for, and those of them already fully loaded. */
    private Set<String> sinks, loadedSinks;
    private Object highestWatermark;

    private long rowsRead, upserts, deletes, unchanged, seq;

    DiffRun(StateStore state, String runId) {
        this.state = state;
        this.outbox = new Outbox(state);
        this.runId = runId;
    }

    RunStats stats() {
        return new RunStats(rowsRead, upserts, deletes, unchanged);
    }

    void run(Job job, RunKind kind, boolean allowDeletes) throws SQLException {
        long indexed = state.indexSize(job.id());
        sinks = outbox.prepare(job.id(), job.sinks().keySet()).values().stream()
            .filter(s -> s.status() != SinkStatus.RESNAPSHOT_REQUIRED).map(Outbox.SinkState::sinkId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        loadedSinks = state.loadedSinks(job.id(), sinks);
        Watermark watermark = job.watermark();
        boolean fullScan = watermark == null || kind == RunKind.RECONCILE;
        ReadSpec spec = job.readSpec();
        if (!fullScan && loadedSinks.containsAll(sinks)) {
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
        // every sink now has every source row read queued; deletes below are sent to all of them
        state.markLoaded(job.id(), sinks);
        loadedSinks = sinks;

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
            List<IndexEntry> removedEntries = entries;
            state.transact(c -> {
                enqueue(job, removed, removed);
                state.remove(job.id(), removedEntries);
                return null;
            });
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
        boolean fullLoad = !loadedSinks.containsAll(sinks);
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
        state.transact(c -> {
            enqueue(job, changed, all);
            state.markSeen(job.id(), runId, entries);
            return null;
        });

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

    /** Queues {@code changed} for fully loaded sinks and {@code all} for sinks still waiting for their full load. */
    private void enqueue(Job job, List<Change> changed, List<Change> all) throws SQLException {
        List<String> loaded = sinks.stream().filter(loadedSinks::contains).toList();
        List<String> loading = sinks.stream().filter(s -> !loadedSinks.contains(s)).toList();
        append(job, loaded, changed);
        append(job, loading, all);
    }

    private void append(Job job, Collection<String> recipients, List<Change> changes) throws SQLException {
        if (recipients.isEmpty() || changes.isEmpty()) return;
        outbox.append(job.id(), recipients, changes.size(),
            id -> Batches.encode(new ChangeBatch(job.id(), null, id, job.mapping().target(), changes)));
    }

    private static String idempotencyKey(Job job, String keyJson, String rowHashOrDel) {
        return Canonical.sha256Hex(job.id() + "|" + keyJson + "|" + rowHashOrDel);
    }
}
