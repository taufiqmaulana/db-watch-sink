package io.datasync.core;

import io.datasync.api.ApplyResult;
import io.datasync.api.CanonicalType;
import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.Column;
import io.datasync.api.HealthStatus;
import io.datasync.api.ObjectRef;
import io.datasync.api.ObjectSchema;
import io.datasync.api.ReadSpec;
import io.datasync.api.RecordCursor;
import io.datasync.api.Sink;
import io.datasync.api.SinkCapabilities;
import io.datasync.api.SinkContext;
import io.datasync.api.SourceConnector;
import io.datasync.core.config.DataSyncConfig.ColumnsDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotDiffTest {

    private static final ObjectSchema SCHEMA = new ObjectSchema(ObjectRef.parse("HR.CUSTOMERS"), List.of(
        new Column("ID", CanonicalType.of(CanonicalType.Kind.INT64), false),
        new Column("NAME", CanonicalType.of(CanonicalType.Kind.STRING), true),
        new Column("BALANCE", CanonicalType.of(CanonicalType.Kind.DECIMAL), true)), List.of("ID"));

    @TempDir
    Path dir;

    private StateStore state;
    private final FakeSource source = new FakeSource();
    private final FakeSink sink = new FakeSink();
    private Job job;

    @BeforeEach
    void setUp() throws Exception {
        state = new StateStore(dir.resolve("state.db"));
        ColumnMapping mapping = ColumnMapping.resolve(SCHEMA, new ColumnsDef(null, null, Map.of("NAME", "customer_name")), List.of());
        ReadSpec spec = new ReadSpec(SCHEMA.ref(), mapping.sourceColumns(), mapping.sourceKeys(), 100, 2, Duration.ofSeconds(30));
        job = new Job("customers", source, spec, mapping, Map.of("memory:a", sink), 40);
        for (long id = 1; id <= 5; id++) source.put(id, "c" + id, "10.00");
    }

    private void useSinks(Map<String, Sink> sinks) {
        job = new Job(job.id(), job.source(), job.readSpec(), job.mapping(), sinks, job.maxDeletePercent());
    }

    @AfterEach
    void tearDown() throws Exception {
        state.close();
    }

    @Test
    void firstRunUpsertsEverythingAndRerunIsANoOp() throws Exception {
        assertThat(run(false)).isEqualTo(new RunStats(5, 5, 0, 0));
        assertThat(sink.target).hasSize(5);
        assertThat(sink.target.get(1L)).containsEntry("customer_name", "c1");

        sink.batches.clear();
        assertThat(run(false)).isEqualTo(new RunStats(5, 0, 0, 5));
        assertThat(sink.batches).isEmpty();
    }

    @Test
    void detectsChangesAndDeletes() throws Exception {
        run(false);
        source.put(2L, "changed", "10.00");
        source.put(6L, "new", "1");
        source.rows.remove(5L);
        source.put(3L, "c3", "10.0");   // same decimal value, different scale: unchanged

        assertThat(run(false)).isEqualTo(new RunStats(5, 2, 1, 3));
        assertThat(sink.target).containsOnlyKeys(1L, 2L, 3L, 4L, 6L);
        assertThat(sink.target.get(2L)).containsEntry("customer_name", "changed");
        assertThat(state.indexSize("customers")).isEqualTo(5);
    }

    @Test
    void changesCarryDeterministicIdempotencyKeys() throws Exception {
        run(false);
        String first = sink.batches.getFirst().changes().getFirst().idempotencyKey();
        tearDown();
        state = new StateStore(dir.resolve("fresh.db"));   // empty index: the same rows are delivered again
        sink.batches.clear();
        run(false);
        assertThat(sink.batches.getFirst().changes().getFirst().idempotencyKey()).isEqualTo(first);
    }

    @Test
    void aSinkAddedLaterGetsEveryRowOnceThenOnlyChanges() throws Exception {
        run(false);
        FakeSink added = new FakeSink();
        useSinks(Map.of("memory:a", sink, "memory:b", added));
        source.put(2L, "changed", "10.00");
        sink.batches.clear();

        assertThat(run(false)).isEqualTo(new RunStats(5, 1, 0, 4));
        assertThat(added.target).hasSize(5);
        assertThat(sink.batches).flatExtracting(ChangeBatch::changes).hasSize(1);

        added.batches.clear();
        source.put(3L, "changed too", "10.00");
        run(false);
        assertThat(added.batches).flatExtracting(ChangeBatch::changes).hasSize(1);
    }

    @Test
    void aSinkRemovedAndAddedBackIsLoadedAgain() throws Exception {
        FakeSink other = new FakeSink();
        useSinks(Map.of("memory:a", sink, "memory:b", other));
        run(false);
        useSinks(Map.of("memory:a", sink));
        run(false);

        other.target.clear();
        useSinks(Map.of("memory:a", sink, "memory:b", other));
        run(false);
        assertThat(other.target).hasSize(5);
    }

    @Test
    void deleteGuardStopsMassDeletesUntilAllowed() throws Exception {
        run(false);
        source.rows.remove(1L);
        source.rows.remove(2L);
        source.rows.remove(3L);

        assertThatThrownBy(() -> run(false)).isInstanceOf(DeleteGuardException.class).hasMessageContaining("3 of 5");
        assertThat(sink.target).hasSize(5);

        assertThat(run(true).deletes()).isEqualTo(3);
        assertThat(sink.target).containsOnlyKeys(4L, 5L);
    }

    @Test
    void deleteGuardTripsOnEmptySource() throws Exception {
        run(false);
        source.rows.clear();
        assertThatThrownBy(() -> run(false)).isInstanceOf(DeleteGuardException.class).hasMessageContaining("no rows");
    }

    @Test
    void failedDeliveryLeavesTheIndexUntouchedSoTheChunkIsResent() throws Exception {
        sink.failing = true;
        assertThatThrownBy(() -> run(false)).hasMessageContaining("sink down");
        assertThat(state.indexSize("customers")).isZero();

        sink.failing = false;
        assertThat(run(false).upserts()).isEqualTo(5);
    }

    private RunStats run(boolean allowDeletes) throws Exception {
        SnapshotDiff diff = new SnapshotDiff(state, state.startRun("customers", "POLL"));
        diff.run(job, allowDeletes);
        return diff.stats();
    }

    static final class FakeSource implements SourceConnector {
        final Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();

        void put(long id, String name, String balance) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ID", id);
            row.put("NAME", name);
            row.put("BALANCE", new BigDecimal(balance));
            rows.put(id, row);
        }

        @Override public String type() { return "fake"; }
        @Override public ObjectSchema describe(ObjectRef ref) { return SCHEMA; }
        @Override public List<ObjectRef> listObjects(String schemaFilter) { return List.of(SCHEMA.ref()); }
        @Override public Set<String> primaryKeyOf(ObjectRef ref) { return Set.of("ID"); }
        @Override public void close() {}

        @Override
        public RecordCursor read(ReadSpec spec) {
            Iterator<Map<String, Object>> it = new ArrayList<>(rows.values()).iterator();
            return new RecordCursor() {
                @Override public boolean hasNext() { return it.hasNext(); }
                @Override public Map<String, Object> next() { return it.next(); }
                @Override public void close() {}
            };
        }
    }

    static final class FakeSink implements Sink {
        final Map<Object, Map<String, Object>> target = new LinkedHashMap<>();
        final List<ChangeBatch> batches = new ArrayList<>();
        boolean failing;

        @Override public String type() { return "memory"; }
        @Override public void init(SinkContext ctx, ObjectSchema schema) {}
        @Override public SinkCapabilities capabilities() { return new SinkCapabilities(true, false, true, 0); }
        @Override public HealthStatus health() { return new HealthStatus(true, null); }
        @Override public void close() {}

        @Override
        public ApplyResult apply(ChangeBatch batch) {
            if (failing) throw new IllegalStateException("sink down");
            batches.add(batch);
            for (Change ch : batch.changes()) {
                Object id = ((Number) ch.key().get("ID")).longValue();
                if (ch.op() == Change.Op.UPSERT) target.put(id, ch.row());
                else target.remove(id);
            }
            return new ApplyResult(batch.changes().size(), 0);
        }
    }
}
