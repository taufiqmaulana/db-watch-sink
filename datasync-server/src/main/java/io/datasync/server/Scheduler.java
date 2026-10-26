package io.datasync.server;

import io.datasync.core.config.DataSyncConfig.JobDef;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fires each job on its schedule (design 5A). Every tick rereads the job, so edits, pausing, deletion and
 * NEEDS_REVIEW take effect on their own; only ACTIVE jobs run. A tick that finds the job still running is
 * skipped, and ticks missed while the server was busy or asleep are not caught up. Ticks fire only while this
 * server holds the scheduler lease.
 */
@Singleton
public class Scheduler {

    private static final String LEASE = "scheduler";
    private static final Duration LEASE_TTL = Duration.ofSeconds(90);
    private static final Duration LEASE_RENEWAL = Duration.ofSeconds(30);

    private record Planned(Instant at, ScheduledFuture<?> tick) {}

    @Inject
    Db db;

    @Inject
    Runs runs;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Planned> planned = new ConcurrentHashMap<>();
    private final String holder = hostName() + "/" + UUID.randomUUID();
    private volatile boolean leader;

    void start(@Observes StartupEvent ignored) {
        timer.scheduleAtFixedRate(this::renewLease, 0, LEASE_RENEWAL.toSeconds(), TimeUnit.SECONDS);
        db.query("SELECT id FROM job", rs -> rs.getString(1)).forEach(this::reschedule);
    }

    /** Plans a job afresh from its stored schedule; call after it is created, edited or deleted. */
    public void reschedule(String jobId) {
        plan(jobId, Instant.now());
    }

    /** When the job will next be considered for a run (it runs only if ACTIVE then). */
    public Optional<Instant> nextRun(String jobId) {
        return Optional.ofNullable(planned.get(jobId)).map(Planned::at);
    }

    /** Cancels the job's pending tick and plans the next one after {@code after}, or none if the job is gone. */
    private synchronized void plan(String jobId, Instant after) {
        Planned previous = planned.remove(jobId);
        if (previous != null) previous.tick().cancel(false);
        JobSchedule schedule = load(jobId).orElse(null);
        if (schedule == null) return;

        Instant now = Instant.now();
        Instant next = schedule.next(after);
        if (next.isBefore(now)) next = schedule.next(now);   // do not catch up on missed ticks
        Instant tickAt = next.plus(schedule.jitter(next));
        Instant base = next;
        ScheduledFuture<?> tick = timer.schedule(() -> fire(jobId, schedule, base),
            Math.max(0, Duration.between(now, tickAt).toMillis()), TimeUnit.MILLISECONDS);
        planned.put(jobId, new Planned(tickAt, tick));
    }

    /** {@code base} is the tick's planned time without jitter, so intervals do not drift. */
    private void fire(String jobId, JobSchedule schedule, Instant base) {
        CompletableFuture<Void> run = null;
        try {
            if (leader && isActive(jobId)) {
                run = runs.start(jobId, false);
                if (run == null) Log.infof("job %s is still running, skipped its scheduled run", jobId);
            }
        } catch (RuntimeException e) {
            Log.errorf(e, "scheduled run of job %s could not start", jobId);
        }
        if (schedule.fixedDelay() && run != null) {
            run.whenComplete((ok, error) -> plan(jobId, Instant.now()));
        } else {
            plan(jobId, schedule.fixedDelay() ? Instant.now() : base);
        }
    }

    private Optional<JobSchedule> load(String jobId) {
        Optional<JobDef> def = db.one("SELECT config_json FROM job WHERE id = ?", rs -> Json.read(rs.getString(1), JobDef.class), jobId);
        try {
            return def.map(JobSchedule::of);
        } catch (IllegalArgumentException e) {
            Log.errorf("job %s is not scheduled: %s", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isActive(String jobId) {
        return db.one("SELECT status FROM job WHERE id = ?", rs -> rs.getString(1), jobId).filter("ACTIVE"::equals).isPresent();
    }

    /** Takes or extends the lease when it is ours or has expired. */
    private void renewLease() {
        Instant now = Instant.now();
        boolean held;
        try {
            held = db.update("INSERT INTO scheduler_lease (name, holder, expires_at) VALUES (?, ?, ?) ON CONFLICT (name) DO UPDATE"
                    + " SET holder = excluded.holder, expires_at = excluded.expires_at"
                    + " WHERE scheduler_lease.holder = excluded.holder OR scheduler_lease.expires_at < ?",
                LEASE, holder, now.plus(LEASE_TTL), now) == 1;
        } catch (RuntimeException e) {
            Log.error("could not renew the scheduler lease", e);
            held = false;
        }
        if (held != leader) Log.infof(held ? "This server now runs scheduled jobs" : "Another server holds the scheduler lease; not running scheduled jobs");
        leader = held;
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
        try {
            db.update("DELETE FROM scheduler_lease WHERE name = ? AND holder = ?", LEASE, holder);
        } catch (RuntimeException e) {
            Log.debug("scheduler lease not released; it expires on its own", e);
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
