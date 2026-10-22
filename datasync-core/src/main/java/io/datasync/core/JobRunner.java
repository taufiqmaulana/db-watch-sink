package io.datasync.core;

import io.datasync.api.ConnectionConfig;
import io.datasync.api.ObjectRef;
import io.datasync.api.ObjectSchema;
import io.datasync.api.ReadSpec;
import io.datasync.api.Sink;
import io.datasync.api.SinkContext;
import io.datasync.api.SinkProvider;
import io.datasync.api.SourceConnector;
import io.datasync.api.SourceProvider;
import io.datasync.core.config.DataSyncConfig;
import io.datasync.core.config.DataSyncConfig.JobDef;
import io.datasync.core.config.DataSyncConfig.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Opens a configured job, runs it once and records the outcome in the state store. */
public final class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final DataSyncConfig config;
    private final StateStore state;

    public JobRunner(DataSyncConfig config, StateStore state) {
        this.config = config;
        this.state = state;
    }

    /** With {@code allowDeletes}, the delete guard is skipped (operator override after review). */
    public RunResult run(String jobName, boolean allowDeletes) throws SQLException {
        JobDef def = config.jobs().stream().filter(j -> j.name().equals(jobName)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown job: " + jobName));
        String runId = state.startRun(def.name(), "POLL");
        SnapshotDiff diff = new SnapshotDiff(state, runId);
        RunState outcome = RunState.SUCCEEDED;
        String error = null;
        try (Job job = open(def)) {
            diff.run(job, allowDeletes);
        } catch (DeleteGuardException e) {
            outcome = RunState.NEEDS_REVIEW;
            error = e.getMessage();
        } catch (SQLException | RuntimeException e) {
            outcome = RunState.FAILED;
            error = describe(e);
            log.error("job {} run {} failed", def.name(), runId, e);
        }
        RunStats stats = diff.stats();
        state.finishRun(runId, outcome, stats, error);
        log.info("job {} run {}: {} (read {}, upserts {}, deletes {}, unchanged {}){}", def.name(), runId, outcome,
            stats.rowsRead(), stats.upserts(), stats.deletes(), stats.unchanged(), error == null ? "" : ": " + error);
        return new RunResult(def.name(), runId, outcome, stats, error);
    }

    private Job open(JobDef def) {
        if (def.mode() != Mode.SNAPSHOT_DIFF) {
            throw new UnsupportedOperationException("mode " + def.mode() + " is not available yet, use SNAPSHOT_DIFF");
        }
        if (def.sinks().isEmpty()) throw new IllegalArgumentException("job " + def.name() + " has no sinks");

        ConnectionConfig sourceConn = connection(def.source().connection());
        SourceConnector source = provider(SourceProvider.class, p -> p.supports(sourceConn.kind()), "connection kind " + sourceConn.kind())
            .open(sourceConn);
        Map<String, Sink> sinks = new LinkedHashMap<>();
        try {
            ObjectSchema schema = source.describe(ObjectRef.parse(def.source().object()));
            ColumnMapping mapping = ColumnMapping.resolve(schema, def.columns(), def.keys());
            for (int i = 0; i < def.sinks().size(); i++) {
                Map<String, Object> cfg = def.sinks().get(i);
                String type = String.valueOf(cfg.get("type"));
                Object conn = cfg.get("connection");
                Sink sink = provider(SinkProvider.class, p -> p.supports(type), "sink type " + type)
                    .open(conn == null ? null : connection(conn.toString()));
                String sinkId = def.name() + "/sink-" + (i + 1);
                sinks.put(sinkId, sink);
                sink.init(new SinkContext(def.name(), sinkId, cfg), mapping.target());
            }
            ReadSpec spec = new ReadSpec(schema.ref(), mapping.sourceColumns(), mapping.sourceKeys(),
                def.fetchSize(), def.batchSize(), Duration.ofSeconds(def.queryTimeoutSeconds()));
            return new Job(def.name(), source, spec, mapping, sinks, def.maxDeletePercent());
        } catch (RuntimeException e) {
            sinks.values().forEach(Sink::close);
            source.close();
            throw e;
        }
    }

    private ConnectionConfig connection(String name) {
        Map<String, Object> def = config.connections().stream().filter(c -> name.equals(c.get("name"))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown connection: " + name));
        Map<String, String> props = new LinkedHashMap<>();
        def.forEach((k, v) -> props.put(k, String.valueOf(v)));
        return new ConnectionConfig(name, props.get("kind"), props);
    }

    private static <P> P provider(Class<P> type, Predicate<P> supports, String what) {
        return ServiceLoader.load(type).stream().map(ServiceLoader.Provider::get).filter(supports).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no connector installed for " + what));
    }

    /** The message chain, e.g. "apply of batch 3 failed: ERROR: permission denied", skipping repeated text. */
    private static String describe(Throwable e) {
        StringBuilder out = new StringBuilder();
        Stream.iterate(e, t -> t != null, Throwable::getCause)
            .map(t -> t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())
            .filter(m -> out.indexOf(m) < 0)
            .forEach(m -> out.append(out.isEmpty() ? "" : ": ").append(m));
        return out.toString();
    }
}
