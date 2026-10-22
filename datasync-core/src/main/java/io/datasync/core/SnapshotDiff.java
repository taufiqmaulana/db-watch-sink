package io.datasync.core;

import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.RecordCursor;
import io.datasync.core.StateStore.IndexEntry;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One SNAPSHOT_DIFF run (design 5.2), in the single-pass MVP variant (design 6): each chunk is diffed
 * against the hash index, applied to every sink, and only then recorded in the index. A crash in between
 * re-sends the chunk on the next run, which idempotent sinks absorb.
 */
final class SnapshotDiff {

    private final StateStore state;
    private final String runId;
    private long rowsRead, upserts, deletes, unchanged, seq, batchId;

    SnapshotDiff(StateStore state, String runId) {
        this.state = state;
        this.runId = runId;
    }

    RunStats stats() {
        return new RunStats(rowsRead, upserts, deletes, unchanged);
    }

    void run(Job job, boolean allowDeletes) throws SQLException {
        long indexed = state.indexSize(job.id());
        int chunkSize = job.readSpec().chunkSize();
        try (RecordCursor cursor = job.source().read(job.readSpec())) {
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

        long gone = state.countUnseen(job.id(), runId);
        if (!allowDeletes) checkDeleteGuard(job, indexed, gone);
        for (List<IndexEntry> entries; !(entries = state.unseen(job.id(), runId, chunkSize)).isEmpty(); ) {
            Instant now = Instant.now();
            deliver(job, entries.stream()
                .map(e -> new Change(Change.Op.DELETE, job.mapping().key(Canonical.parse(e.keyJson())), null, null,
                    idempotencyKey(job, e.keyJson(), "del"), now, ++seq))
                .toList());
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
            String keyJson = Canonical.json(row, mapping.sourceKeys());
            entries.add(new IndexEntry(Canonical.hash(keyJson), keyJson, Canonical.hash(Canonical.values(row, mapping.sourceColumns()))));
        }

        Map<String, String> known = state.rowHashes(job.id(), entries.stream().map(IndexEntry::keyHash).toList());
        Instant now = Instant.now();
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            IndexEntry e = entries.get(i);
            if (e.rowHash().equals(known.get(e.keyHash()))) continue;
            changes.add(new Change(Change.Op.UPSERT, mapping.key(rows.get(i)), mapping.row(rows.get(i)), e.rowHash(),
                idempotencyKey(job, e.keyJson(), e.rowHash()), now, ++seq));
        }
        deliver(job, changes);
        state.markSeen(job.id(), runId, entries);

        rowsRead += rows.size();
        upserts += changes.size();
        unchanged += rows.size() - changes.size();
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

    private void deliver(Job job, List<Change> changes) {
        if (changes.isEmpty()) return;
        batchId++;
        job.sinks().forEach((sinkId, sink) ->
            sink.apply(new ChangeBatch(job.id(), sinkId, batchId, job.mapping().target(), changes)));
    }

    private static String idempotencyKey(Job job, String keyJson, String rowHashOrDel) {
        return Canonical.sha256Hex(job.id() + "|" + keyJson + "|" + rowHashOrDel);
    }
}
