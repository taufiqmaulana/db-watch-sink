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
import io.datasync.api.SinkException;
import io.datasync.api.SourceConnector;
import io.datasync.api.WatermarkSpec;
import io.datasync.core.config.DataSyncConfig.ColumnsDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiffRunTest {

    private static final ObjectSchema SCHEMA = new ObjectSchema(ObjectRef.parse("HR.CUSTOMERS"), List.of(
        new Column("ID", CanonicalType.of(CanonicalType.Kind.INT64), false),
        new Column("NAME", CanonicalType.of(CanonicalType.Kind.STRING), true),
        new Column("BALANCE", CanonicalType.of(CanonicalType.Kind.DECIMAL), true),
        new Column("UPDATED", new CanonicalType(CanonicalType.Kind.TIMESTAMP, 6, 0), true)), List.of("ID"));

    @TempDir
    Path dir;

    private StateStore state;
    private final FakeSource source = new FakeSource();
    private final FakeSink sink = new FakeSink();
    private Job job;

    @BeforeEach
    void setUp() throws Exception {
        state = new StateStore(dir.resolve("state.db"));
        ColumnMapping mapping = ColumnMapping.resolve(SCHEMA, new ColumnsDef(null, List.of("UPDATED"), Map.of("NAME", "customer_name")), List.of(), false);
        ReadSpec spec = new ReadSpec(SCHEMA.ref(), mapping.sourceColumns(), mapping.sourceKeys(), 100, 2, Duration.ofSeconds(30));
        job = new Job("customers", source, spec, mapping, Map.of("memory:a", sink), 40);
        for (long id = 1; id <= 5; id++) source.put(id, "c" + id, "10.00");
    }

    private void useSinks(Map<String, Sink> sinks) {
        job = new Job(job.id(), job.source(), job.readSpec(), job.mapping(), sinks, job.maxDeletePercent(), job.watermark());
    }

    /** The single sink "memory:a" with this delivery policy, keeping rows when it asks for deleted ones. */
    private void usePolicy(String maxAttempts, String onDeadLetter) {
        policies.put("memory:a", policy(maxAttempts, onDeadLetter));
        job = new Job(job.id(), job.source(), job.readSpec(), job.mapping(), Map.of("memory:a", sink), job.maxDeletePercent(),
            job.watermark(), sink.wantsDeletedRows);
    }

    private static DeliveryPolicy policy(String maxAttempts, String onDeadLetter) {
        return DeliveryPolicy.of(Map.of("retry", Map.of("maxAttempts", maxAttempts, "initialBackoff", "0s"), "onDeadLetter", onDeadLetter));
    }

    private List<String> deadLetters() throws Exception {
        return state.transact(c -> {
            List<String> payloads = new ArrayList<>();
            try (var rs = c.createStatement().executeQuery("SELECT payload FROM dead_letter ORDER BY created_at")) {
                while (rs.next()) payloads.add(rs.getString(1));
            }
            return payloads;
        });
    }

    /** Makes the job a WATERMARK job on UPDATED with a 5 minute overlap; rows 1..5 were updated 10 minutes apart. */
    private void useWatermark() {
        job = new Job(job.id(), job.source(), job.readSpec(), job.mapping(), job.sinks(), job.maxDeletePercent(),
            new Watermark("UPDATED", SCHEMA.columns().get(3).type(), Duration.ofMinutes(5)));
        for (long id = 1; id <= 5; id++) {
            source.clock = LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(10 * id);
            source.put(id, "c" + id, "10.00");
        }
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
    void watermarkRunsReadOnlyRowsSinceTheCursorMinusTheOverlap() throws Exception {
        useWatermark();
        assertThat(run(false)).as("no cursor yet: full read").isEqualTo(new RunStats(5, 5, 0, 0));

        // cursor 00:50, overlap 5m: only row 5 (00:50) is re-read, and dropped as unchanged
        assertThat(run(false)).isEqualTo(new RunStats(1, 0, 0, 1));

        source.clock = LocalDateTime.of(2026, 1, 1, 2, 0);
        source.put(2L, "changed", "10.00");
        assertThat(run(false)).isEqualTo(new RunStats(2, 1, 0, 1));
        assertThat(sink.target.get(2L)).containsEntry("customer_name", "changed");
    }

    @Test
    void watermarkRunsNeverDeleteButAReconcileDoes() throws Exception {
        useWatermark();
        run(false);
        source.rows.remove(3L);

        assertThat(run(false).deletes()).isZero();
        assertThat(sink.target).containsKey(3L);

        assertThat(run(RunKind.RECONCILE, false)).isEqualTo(new RunStats(4, 0, 1, 4));
        assertThat(sink.target).doesNotContainKey(3L);
    }

    @Test
    void aSinkAddedToAWatermarkJobGetsAFullRead() throws Exception {
        useWatermark();
        run(false);
        FakeSink added = new FakeSink();
        useSinks(Map.of("memory:a", sink, "memory:b", added));

        assertThat(run(false).rowsRead()).isEqualTo(5);
        assertThat(added.target).hasSize(5);
        assertThat(run(false).rowsRead()).as("back to watermark reads").isEqualTo(1);
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
    void retryableFailuresAreRetriedAndThenSucceed() throws Exception {
        usePolicy("3", "PAUSE");
        sink.failures = 2;
        assertThat(run(false).upserts()).isEqualTo(5);
        assertThat(sink.target).hasSize(5);
        assertThat(deadLetters()).isEmpty();
        assertThat(delivered("memory:a")).extracting(DeliveryResult::failedAttempts, DeliveryResult::deadLetters).containsExactly(2L, 0L);
        assertThat(outboxSize()).as("delivered batches are trimmed").isZero();
    }

    @Test
    void aFailingSinkDoesNotHoldUpTheOthersAndCatchesUpLater() throws Exception {
        FakeSink other = new FakeSink();
        useSinks(Map.of("memory:a", sink, "memory:b", other));
        policies.put("memory:a", policy("5", "PAUSE"));
        sink.failing = true;

        runWithoutWaiting();
        assertThat(other.target).hasSize(5);
        assertThat(sink.target).isEmpty();
        DeliveryResult waiting = delivered("memory:a");
        assertThat(waiting.backlog()).as("all 3 batches still queued").isEqualTo(3);
        assertThat(waiting.retryAt()).isNotNull();
        assertThat(waiting.error()).contains("sink down");
        assertThat(state.indexSize("customers")).as("the run itself completed").isEqualTo(5);

        sink.failing = false;
        source.put(2L, "changed", "10.00");
        runWithoutWaiting();
        assertThat(sink.target).hasSize(5).extractingByKey(2L).satisfies(r -> assertThat(r).containsEntry("customer_name", "changed"));
        assertThat(delivered("memory:a").caughtUp()).isTrue();
    }

    @Test
    void aSinkTooFarBehindNeedsAResnapshotWhichLoadsItAgain() throws Exception {
        FakeSink other = new FakeSink();
        useSinks(Map.of("memory:a", sink, "memory:b", other));
        policies.put("memory:a", policy("5", "PAUSE"));
        sink.failing = true;
        runWithoutWaiting();
        Outbox outbox = new Outbox(state);
        assertThat(outbox.flagLagging("customers", 2)).containsExactly("memory:a");
        assertThat(outbox.sinkState("customers", "memory:a").status()).isEqualTo(Outbox.SinkStatus.RESNAPSHOT_REQUIRED);

        sink.failing = false;
        source.put(6L, "new", "1");
        runWithoutWaiting();
        assertThat(sink.target).as("nothing until resnapshotted").isEmpty();
        assertThat(other.target).hasSize(6);

        outbox.resnapshot("customers", "memory:a");
        other.batches.clear();
        runWithoutWaiting();
        assertThat(sink.target).hasSize(6);
        assertThat(other.batches).as("the other sink only gets changes").isEmpty();
    }

    @Test
    void aPermanentFailureIsDeadLetteredAtOnceAndSkipCarriesOn() throws Exception {
        usePolicy("5", "SKIP");
        sink.failures = 1;
        sink.permanent = true;

        assertThat(run(false).upserts()).isEqualTo(5);
        assertThat(sink.target).hasSize(3).as("the first chunk of 2 went to the dead letters");
        List<String> letters = deadLetters();
        assertThat(letters).hasSize(1);
        ChangeBatch replay = Batches.decode(letters.getFirst());
        assertThat(replay.changes()).extracting(c -> c.key().get("ID")).containsExactly(1L, 2L);
        assertThat(replay.changes().getFirst().row()).containsEntry("customer_name", "c1");
        assertThat((BigDecimal) replay.changes().getFirst().row().get("BALANCE")).isEqualByComparingTo("10.00");
        assertThat(state.indexSize("customers")).as("skipped batches still count as seen").isEqualTo(5);
        assertThat(delivered("memory:a")).extracting(DeliveryResult::failedAttempts, DeliveryResult::deadLetters).containsExactly(1L, 1L);
    }

    @Test
    void exhaustedRetriesWithPausePauseTheSinkUntilResumed() throws Exception {
        usePolicy("2", "PAUSE");
        sink.failures = 2;
        assertThat(run(false).upserts()).as("the run itself succeeds").isEqualTo(5);
        assertThat(deadLetters()).hasSize(1);
        assertThat(sink.target).isEmpty();
        DeliveryResult paused = delivered("memory:a");
        assertThat(paused.status()).isEqualTo(Outbox.SinkStatus.PAUSED);
        assertThat(paused.error()).contains("503 Service Unavailable");
        assertThat(paused.backlog()).isEqualTo(2);

        new Outbox(state).resume("customers", "memory:a");
        deliver(true);
        assertThat(sink.target).as("the dead-lettered first batch stays in the dead letters").containsOnlyKeys(3L, 4L, 5L);
    }

    @Test
    void batchesAreSplitToTheSinksLimitAndDeletesCarryTheLastRowWhenAsked() throws Exception {
        sink.batchLimit = 1;
        sink.wantsDeletedRows = true;
        usePolicy("1", "PAUSE");
        run(false);
        assertThat(sink.batches).hasSize(5).allSatisfy(b -> assertThat(b.changes()).hasSize(1));

        source.rows.remove(4L);
        run(false);
        assertThat(sink.deleted).singleElement()
            .satisfies(d -> assertThat(d.row()).containsEntry("ID", 4L).containsEntry("customer_name", "c4"));
    }

    private RunStats run(boolean allowDeletes) throws Exception {
        return run(RunKind.POLL, allowDeletes);
    }

    /** A run, then delivery to every sink retrying in place, as the CLI does. */
    private RunStats run(RunKind kind, boolean allowDeletes) throws Exception {
        DiffRun diff = new DiffRun(state, state.startRun("customers", kind.name()));
        try {
            diff.run(job, kind, allowDeletes);
        } finally {
            deliver(true);
        }
        return diff.stats();
    }

    /** A run, then one delivery pass that records failures for later, as the server does. */
    private void runWithoutWaiting() throws Exception {
        new DiffRun(state, state.startRun("customers", "POLL")).run(job, RunKind.POLL, false);
        deliver(false);
    }

    private void deliver(boolean waitForRetries) throws Exception {
        for (Map.Entry<String, Sink> s : job.sinks().entrySet()) {
            DeliveryPolicy policy = policies.getOrDefault(s.getKey(), policy("1", "PAUSE"));
            deliveries.put(s.getKey(), new SinkDelivery(state, job.id(), s.getKey(), policy, schema -> s.getValue()).run(waitForRetries));
        }
    }

    private long outboxSize() throws Exception {
        return state.transact(c -> {
            try (var rs = c.createStatement().executeQuery("SELECT count(*) FROM outbox_batch")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private DeliveryResult delivered(String sinkId) {
        return deliveries.get(sinkId);
    }

    private final Map<String, DeliveryPolicy> policies = new HashMap<>();
    private final Map<String, DeliveryResult> deliveries = new HashMap<>();

    static final class FakeSource implements SourceConnector {
        final Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        /** The UPDATED value given to rows put from now on. */
        LocalDateTime clock = LocalDateTime.of(2026, 1, 1, 0, 0);

        void put(long id, String name, String balance) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ID", id);
            row.put("NAME", name);
            row.put("BALANCE", new BigDecimal(balance));
            row.put("UPDATED", clock);
            rows.put(id, row);
        }

        @Override public String type() { return "fake"; }
        @Override public ObjectSchema describe(ObjectRef ref) { return SCHEMA; }
        @Override public List<ObjectRef> listObjects(String schemaFilter) { return List.of(SCHEMA.ref()); }
        @Override public Set<String> primaryKeyOf(ObjectRef ref) { return Set.of("ID"); }
        @Override public void close() {}

        @Override
        public RecordCursor read(ReadSpec spec) {
            WatermarkSpec wm = spec.watermark();
            Iterator<Map<String, Object>> it = rows.values().stream()
                .filter(r -> wm == null || r.get(wm.column()) != null
                    && (wm.from() == null || ((LocalDateTime) r.get(wm.column())).compareTo((LocalDateTime) wm.from()) >= 0))
                .toList().iterator();
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
        /** The next this many apply calls fail, retryably unless {@code permanent}. */
        int failures;
        boolean permanent;
        int batchLimit;
        boolean wantsDeletedRows;
        final List<Change> deleted = new ArrayList<>();

        @Override public String type() { return "memory"; }
        @Override public void init(SinkContext ctx, ObjectSchema schema) {}
        @Override public SinkCapabilities capabilities() { return new SinkCapabilities(true, false, true, batchLimit, false, wantsDeletedRows); }
        @Override public HealthStatus health() { return new HealthStatus(true, null); }
        @Override public void close() {}

        @Override
        public ApplyResult apply(ChangeBatch batch) {
            if (failing) throw new IllegalStateException("sink down");
            if (failures > 0) {
                failures--;
                throw permanent ? SinkException.permanent("400 Bad Request", null) : SinkException.retryable("503 Service Unavailable", null, null);
            }
            batches.add(batch);
            for (Change ch : batch.changes()) {
                Object id = ((Number) ch.key().get("ID")).longValue();
                if (ch.op() == Change.Op.UPSERT) target.put(id, ch.row());
                else {
                    target.remove(id);
                    deleted.add(ch);
                }
            }
            return new ApplyResult(batch.changes().size(), 0);
        }
    }
}
