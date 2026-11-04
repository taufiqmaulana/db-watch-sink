package io.datasync.server;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background upkeep: every minute, refreshes the lag and dead-letter gauges and checks the lag alert; every hour, prunes
 * finished runs and resolved dead letters older than the retention period (design 12). Each job keeps its latest run.
 */
@Singleton
public class Housekeeping {

    /** When a job last had a successful run, or was created if it never had one. */
    public record Freshness(String id, String name, boolean active, Instant lastSuccess) {}

    @Inject
    Db db;

    @Inject
    Settings settings;

    @Inject
    Metrics metrics;

    @Inject
    Alerts alerts;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("housekeeping").factory());

    void start(@Observes StartupEvent ignored) {
        timer.scheduleWithFixedDelay(() -> guard("refresh", this::refresh), 5, 60, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> guard("prune", this::prune), 1, 60, TimeUnit.MINUTES);
    }

    /** Updates the gauges and checks lag; also called after each run. */
    public void refresh() {
        List<Freshness> jobs = db.query("SELECT j.id, j.name, j.status, j.created_at,"
                + " (SELECT max(r.started_at) FROM run r WHERE r.job_id = j.id AND r.state = 'SUCCEEDED') AS last_ok FROM job j",
            rs -> new Freshness(rs.getString("id"), rs.getString("name"), "ACTIVE".equals(rs.getString("status")),
                Objects.requireNonNullElse(Db.instant(rs, "last_ok"), Db.instant(rs, "created_at"))));
        metrics.refresh(jobs);
        alerts.checkLag(jobs);
    }

    /** Returns how many runs and dead letters were removed. */
    public int prune() {
        Instant before = Instant.now().minus(Duration.ofDays(settings.get().retentionDays()));
        int removed = db.tx(c -> Db.update(c, "DELETE FROM run WHERE finished_at < ? AND id NOT IN (SELECT id FROM"
                + " (SELECT id, row_number() OVER (PARTITION BY job_id ORDER BY started_at DESC) AS rn FROM run) WHERE rn = 1)", before)
            + Db.update(c, "DELETE FROM dead_letter WHERE resolved_at < ?", before));
        if (removed > 0) Log.infof("pruned %d runs and resolved dead letters from before %s", removed, before);
        return removed;
    }

    private static void guard(String task, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            Log.errorf(e, "housekeeping %s failed", task);
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}
