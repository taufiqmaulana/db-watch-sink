package io.datasync.server;

import io.datasync.core.RunResult;
import io.datasync.core.RunStats;
import io.datasync.server.Housekeeping.Freshness;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Engine metrics at /metrics (design 12): {@code datasync_run_duration_seconds{job}}, {@code datasync_rows_total{job,kind}},
 * {@code datasync_sink_errors_total{job,sink}} (failed delivery attempts), {@code datasync_lag_seconds{job}} (time since the
 * start of the last successful run) and {@code datasync_dead_letters{job,sink}} (open dead letters). Sinks are labelled with
 * connection names. The gauges are refreshed every minute and after each run.
 */
@Singleton
public class Metrics {

    @Inject
    MeterRegistry registry;

    @Inject
    Db db;

    @Inject
    JobConfigs configs;

    private MultiGauge lag;
    private MultiGauge deadLetters;

    @PostConstruct
    void init() {
        lag = MultiGauge.builder("datasync.lag.seconds").description("Seconds since the start of the job's last successful run")
            .register(registry);
        deadLetters = MultiGauge.builder("datasync.dead.letters").description("Dead-lettered batches waiting to be retried or discarded")
            .register(registry);
    }

    public void recordRun(String jobName, RunResult result, Duration took) {
        Timer.builder("datasync.run.duration").description("Run duration").tag("job", jobName).register(registry).record(took);
        RunStats s = result.stats();
        Map.of("read", s.rowsRead(), "upsert", s.upserts(), "delete", s.deletes(), "unchanged", s.unchanged()).forEach((kind, n) ->
            registry.counter("datasync.rows", "job", jobName, "kind", kind).increment(n));
        result.sinks().forEach((sink, f) ->
            registry.counter("datasync.sink.errors", "job", jobName, "sink", configs.sinkLabel(sink)).increment(f.attempts()));
    }

    /** Replaces the gauge rows, so deleted jobs and resolved dead letters disappear. */
    public void refresh(List<Freshness> jobs) {
        lag.register(jobs.stream().<MultiGauge.Row<?>>map(j -> MultiGauge.Row.of(Tags.of("job", j.name()), j.lastSuccess(),
            (Instant t) -> Duration.between(t, Instant.now()).toMillis() / 1000.0)).toList(), true);
        deadLetters.register(db.<MultiGauge.Row<?>>query("SELECT j.name, d.sink_id, count(*) FROM dead_letter d JOIN job j ON j.id = d.job_id"
                + " WHERE d.resolved_at IS NULL GROUP BY j.name, d.sink_id",
            rs -> MultiGauge.Row.of(Tags.of("job", rs.getString(1), "sink", configs.sinkLabel(rs.getString(2))), rs.getLong(3))), true);
    }
}
