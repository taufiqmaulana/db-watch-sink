package io.datasync.server;

import io.datasync.core.RunKind;
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
 * Fires each job on its schedule (design 5A), and HYBRID jobs also on their reconcile schedule (design 5.4).
 * Every tick rereads the job, so edits, pausing, deletion and NEEDS_REVIEW take effect on their own; only ACTIVE
 * jobs run. A poll that finds the job still running is skipped; a reconcile retries a minute later instead, so a
 * frequent poll cannot starve it. Ticks missed while the server was busy or asleep are not caught up. Ticks fire
 * only while this server holds the scheduler lease.
 */
@Singleton
public class Scheduler {

    private static final String LEASE = "scheduler";
    private static final Duration LEASE_TTL = Duration.ofSeconds(90);
    private static final Duration LEASE_RENEWAL = Duration.ofSeconds(30);
    private static final Duration RECONCILE_RETRY = Duration.ofMinutes(1);

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

    /** Plans a job afresh from its stored schedules; call after it is created, edited or deleted. */
    public void reschedule(String jobId) {
        for (RunKind kind : RunKind.values()) plan(jobId, kind, Instant.now());
    }

    /** When the job will next be considered for a run of this kind (it runs only if ACTIVE then). */
    public Optional<Instant> next(String jobId, RunKind kind) {
        return Optional.ofNullable(planned.get(key(jobId, kind))).map(Planned::at);
    }

    /** Cancels the pending tick and plans the next one after {@code after}, or none if there is no such schedule. */
    private synchronized void plan(String jobId, RunKind kind, Instant after) {
        JobSchedule schedule = load(jobId, kind).orElse(null);
        if (schedule == null) {
            cancel(jobId, kind);
            return;
        }
        Instant now = Instant.now();
        Instant next = schedule.next(after);
        if (next.isBefore(now)) next = schedule.next(now);   // do not catch up on missed ticks
        planAt(jobId, kind, schedule, next, next.plus(schedule.jitter(next)));
    }

    private synchronized void planAt(String jobId, RunKind kind, JobSchedule schedule, Instant base, Instant tickAt) {
        cancel(jobId, kind);
        ScheduledFuture<?> tick = timer.schedule(() -> fire(jobId, kind, schedule, base),
            Math.max(0, Duration.between(Instant.now(), tickAt).toMillis()), TimeUnit.MILLISECONDS);
        planned.put(key(jobId, kind), new Planned(tickAt, tick));
    }

    private void cancel(String jobId, RunKind kind) {
        Planned previous = planned.remove(key(jobId, kind));
        if (previous != null) previous.tick().cancel(false);
    }

    /** {@code base} is the tick's planned time without jitter, so intervals do not drift. */
    private void fire(String jobId, RunKind kind, JobSchedule schedule, Instant base) {
        CompletableFuture<Void> run = null;
        boolean due = false;
        try {
            due = leader && isActive(jobId);
            if (due) {
                run = runs.start(jobId, kind, false);
                if (run == null) Log.infof("job %s is still running, %s its scheduled %s", jobId,
                    kind == RunKind.RECONCILE ? "retrying" : "skipped", kind.name().toLowerCase());
            }
        } catch (RuntimeException e) {
            Log.errorf(e, "scheduled %s of job %s could not start", kind.name().toLowerCase(), jobId);
        }
        if (due && run == null && kind == RunKind.RECONCILE) {
            Instant retry = Instant.now().plus(RECONCILE_RETRY);
            planAt(jobId, kind, schedule, base, retry);
        } else if (schedule.fixedDelay() && run != null) {
            run.whenComplete((ok, error) -> plan(jobId, kind, Instant.now()));
        } else {
            plan(jobId, kind, schedule.fixedDelay() ? Instant.now() : base);
        }
    }

    private Optional<JobSchedule> load(String jobId, RunKind kind) {
        Optional<JobDef> def = db.one("SELECT config_json FROM job WHERE id = ?", rs -> Json.read(rs.getString(1), JobDef.class), jobId);
        try {
            return kind == RunKind.POLL ? def.map(JobSchedule::of) : def.flatMap(JobSchedule::reconcile);
        } catch (IllegalArgumentException e) {
            Log.errorf("job %s has no %s schedule: %s", jobId, kind.name().toLowerCase(), e.getMessage());
            return Optional.empty();
        }
    }

    private static String key(String jobId, RunKind kind) {
        return jobId + "#" + kind;
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
