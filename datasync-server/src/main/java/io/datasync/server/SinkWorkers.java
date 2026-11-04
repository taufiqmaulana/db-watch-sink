package io.datasync.server;

import io.datasync.core.DeliveryResult;
import io.datasync.core.JobRunner;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One delivery worker per (job, sink) (design 6): each drains its sink's batches from the job outbox on a virtual
 * thread, so a slow or failing sink holds up no other. Workers start after each run and whenever a sink's retry
 * is due (checked every few seconds); at most one per sink runs at a time. Delivery continues while a job is paused,
 * since pausing stops reading, not sending what was already read.
 */
@Singleton
public class SinkWorkers {

    private static final long CHECK_SECONDS = 3;

    @Inject
    Db db;

    @Inject
    JobConfigs configs;

    @Inject
    Metrics metrics;

    @Inject
    Alerts alerts;

    @Inject
    Events events;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("sink-workers").factory());
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    void start(@Observes StartupEvent ignored) {
        timer.scheduleWithFixedDelay(this::startDue, CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS);
    }

    /** Starts the workers of every sink of the job, e.g. after a run queued new batches. */
    public void kick(String jobId) {
        db.query("SELECT sink_id FROM sink_offset WHERE job_id = ? AND status = 'ACTIVE'", rs -> rs.getString(1), jobId)
            .forEach(sinkId -> start(jobId, sinkId));
    }

    public boolean isDelivering(String jobId, String sinkId) {
        return busy.contains(jobId + "\0" + sinkId);
    }

    /** Active sinks with batches waiting whose retry, if any, is due. */
    private void startDue() {
        try {
            db.query("SELECT o.job_id, o.sink_id FROM sink_offset o WHERE o.status = 'ACTIVE' AND (o.next_retry_at IS NULL OR o.next_retry_at <= ?)"
                    + " AND EXISTS (SELECT 1 FROM outbox_batch b WHERE b.job_id = o.job_id AND b.batch_id > o.last_acked_batch"
                    + " AND instr(b.recipients, '|' || o.sink_id || '|') > 0)", rs -> new String[] {rs.getString(1), rs.getString(2)}, Instant.now())
                .forEach(s -> start(s[0], s[1]));
        } catch (RuntimeException e) {
            Log.errorf(e, "could not check for sinks with batches waiting");
        }
    }

    private void start(String jobId, String sinkId) {
        String key = jobId + "\0" + sinkId;
        if (!busy.add(key)) return;
        executor.execute(() -> {
            try {
                var def = configs.job(jobId);
                DeliveryResult result = new JobRunner(configs.engineConfig(jobId, def), db.store()).deliver(jobId, sinkId, false);
                if (result != null && (result.batches() > 0 || result.failedAttempts() > 0)) {
                    metrics.recordDelivery(def.name(), result);
                    alerts.afterDelivery(jobId, def.name(), result);
                    events.publish(new Events.Event("sink-changed", jobId, null, String.valueOf(result.status())));
                }
            } catch (Exception e) {
                Log.errorf(e, "delivery of job %s to sink %s failed", jobId, sinkId);
            } finally {
                busy.remove(key);
            }
        });
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
        executor.shutdownNow();
    }
}
