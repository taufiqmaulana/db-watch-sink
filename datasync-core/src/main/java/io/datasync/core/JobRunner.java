package io.datasync.core;

import io.datasync.api.ConnectionConfig;
import io.datasync.api.ObjectRef;
import io.datasync.api.ObjectSchema;
import io.datasync.api.ReadSpec;
import io.datasync.api.RecordCursor;
import io.datasync.api.Sink;
import io.datasync.api.SinkContext;
import io.datasync.api.SourceConnector;
import io.datasync.core.config.DataSyncConfig;
import io.datasync.core.config.DataSyncConfig.JobDef;
import io.datasync.core.config.DataSyncConfig.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
        JobDef def = job(jobName);
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
            error = Errors.describe(e);
            log.error("job {} run {} failed", def.name(), runId, e);
        }
        RunStats stats = diff.stats();
        state.finishRun(runId, outcome, stats, error);
        log.info("job {} run {}: {} (read {}, upserts {}, deletes {}, unchanged {}){}", def.name(), runId, outcome,
            stats.rowsRead(), stats.upserts(), stats.deletes(), stats.unchanged(), error == null ? "" : ": " + error);
        return new RunResult(def.name(), runId, outcome, stats, error);
    }

    /** Dry run: resolves the column mapping and reads the first {@code sampleRows} rows. Touches no sink. */
    public Preview preview(String jobName, int sampleRows) {
        JobDef def = job(jobName);
        try (SourceConnector source = Connectors.openSource(connection(def.source().connection()))) {
            ObjectSchema schema = source.describe(ObjectRef.parse(def.source().object()));
            ColumnMapping mapping = ColumnMapping.resolve(schema, def.columns(), def.keys());
            List<Map<String, Object>> rows = new ArrayList<>();
            ReadSpec spec = new ReadSpec(schema.ref(), mapping.sourceColumns(), mapping.sourceKeys(),
                sampleRows, sampleRows, Duration.ofSeconds(def.queryTimeoutSeconds()));
            try (RecordCursor cursor = source.read(spec)) {
                while (rows.size() < sampleRows && cursor.hasNext()) rows.add(mapping.row(cursor.next()));
            }
            List<Preview.ColumnPreview> columns = new ArrayList<>();
            for (int i = 0; i < mapping.sourceColumns().size(); i++) {
                var target = mapping.target().columns().get(i);
                columns.add(new Preview.ColumnPreview(mapping.sourceColumns().get(i), target.name(), target.type(),
                    mapping.target().keyColumns().contains(target.name()), target.nullable()));
            }
            return new Preview(columns, rows);
        }
    }

    private JobDef job(String jobName) {
        return config.jobs().stream().filter(j -> j.name().equals(jobName)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown job: " + jobName));
    }

    private Job open(JobDef def) {
        if (def.mode() != Mode.SNAPSHOT_DIFF) {
            throw new UnsupportedOperationException("mode " + def.mode() + " is not available yet, use SNAPSHOT_DIFF");
        }
        if (def.sinks().isEmpty()) throw new IllegalArgumentException("job " + def.name() + " has no sinks");

        SourceConnector source = Connectors.openSource(connection(def.source().connection()));
        Map<String, Sink> sinks = new LinkedHashMap<>();
        try {
            ObjectSchema schema = source.describe(ObjectRef.parse(def.source().object()));
            ColumnMapping mapping = ColumnMapping.resolve(schema, def.columns(), def.keys());
            for (Map<String, Object> cfg : def.sinks()) {
                String sinkId = sinkId(cfg);
                if (sinks.containsKey(sinkId)) throw new IllegalArgumentException("two sinks of job " + def.name() + " write to " + sinkId);
                Object conn = cfg.get("connection");
                Sink sink = Connectors.openSink(String.valueOf(cfg.get("type")), conn == null ? null : connection(conn.toString()));
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

    /**
     * What a sink writes to, e.g. {@code jdbc:pg_dw:public.customers}. It stays the same when other sinks are
     * added or removed, and changes when the target does, which then triggers a full load (see {@link SnapshotDiff}).
     */
    public static String sinkId(Map<String, Object> sink) {
        return Stream.of("type", "connection", "target").map(sink::get).filter(Objects::nonNull).map(Object::toString)
            .collect(Collectors.joining(":"));
    }

    private ConnectionConfig connection(String name) {
        Map<String, Object> def = config.connections().stream().filter(c -> name.equals(c.get("name"))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown connection: " + name));
        Map<String, String> props = new LinkedHashMap<>();
        def.forEach((k, v) -> {
            if (v != null) props.put(k, v.toString());
        });
        return new ConnectionConfig(name, props.get("kind"), props);
    }
}
