package io.datasync.server;

import io.datasync.core.JobRunner;
import io.datasync.core.RunKind;
import io.datasync.core.RunResult;
import io.datasync.core.RunState;
import io.datasync.core.config.DataSyncConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Runs jobs in the background on virtual threads, one run per job at a time, and reads run history. */
@Singleton
public class Runs {

    public record RunView(String id, String jobId, String kind, RunState state, Instant startedAt, @Schema(nullable = true) Instant finishedAt,
                          long rowsRead, long upserts, long deletes, long unchanged, @Schema(nullable = true) String error) {}

    private static final String COLUMNS = "id, job_id, kind, state, started_at, finished_at, rows_read, upserts, deletes, unchanged, error";

    @Inject
    Db db;

    @Inject
    JobConfigs configs;

    @Inject
    Events events;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, Semaphore> connectionSlots = new ConcurrentHashMap<>();

    void recoverInterrupted(@Observes StartupEvent ignored) {
        db.update("UPDATE run SET state = 'FAILED', finished_at = ?, error = 'interrupted by a restart' WHERE state = 'RUNNING'",
            Instant.now());
    }

    public boolean isRunning(String jobId) {
        return running.contains(jobId);
    }

    /**
     * Starts a run in the background and returns its completion, or null if the job is already running.
     * The run first takes a slot on every connection it uses, so jobs sharing a database stay within
     * that connection's max concurrency.
     */
    public CompletableFuture<Void> start(String jobId, RunKind kind, boolean allowDeletes) {
        DataSyncConfig config = configs.engineConfig(jobId, configs.job(jobId));
        if (!running.add(jobId)) return null;
        events.publish(new Events.Event("run-started", jobId, null, RunState.RUNNING.name()));
        return CompletableFuture.runAsync(() -> {
            String runId = null;
            RunState state = RunState.FAILED;
            List<Semaphore> slots = slots(config);
            slots.forEach(Semaphore::acquireUninterruptibly);
            try {
                RunResult result = new JobRunner(config, db.store()).run(jobId, kind, allowDeletes);
                runId = result.runId();
                state = result.state();
                if (state == RunState.NEEDS_REVIEW) {
                    db.update("UPDATE job SET status = 'NEEDS_REVIEW' WHERE id = ?", jobId);
                } else if (state == RunState.SUCCEEDED) {
                    db.update("UPDATE job SET status = 'ACTIVE' WHERE id = ? AND status = 'NEEDS_REVIEW'", jobId);
                }
            } catch (Exception e) {
                Log.errorf(e, "run of job %s could not be recorded", jobId);
            } finally {
                slots.forEach(Semaphore::release);
                running.remove(jobId);
                events.publish(new Events.Event("run-finished", jobId, runId, state.name()));
            }
        }, executor);
    }

    /**
     * One semaphore per connection, sized by its max concurrency, taken in id order so two runs never wait
     * on each other. The size is part of the key, so a changed limit takes effect for new runs.
     */
    private List<Semaphore> slots(DataSyncConfig config) {
        return config.connections().stream()
            .sorted(Comparator.comparing(c -> String.valueOf(c.get("name"))))
            .map(c -> {
                int max = Integer.parseInt(String.valueOf(c.getOrDefault("maxConcurrency", 2)));
                return connectionSlots.computeIfAbsent(c.get("name") + "#" + max, k -> new Semaphore(max));
            })
            .toList();
    }

    public List<RunView> forJob(String jobId, int limit) {
        return db.query("SELECT " + COLUMNS + " FROM run WHERE job_id = ? ORDER BY started_at DESC LIMIT ?", Runs::view, jobId, limit);
    }

    public Optional<RunView> get(String runId) {
        return db.one("SELECT " + COLUMNS + " FROM run WHERE id = ?", Runs::view, runId);
    }

    public List<RunView> since(Instant from) {
        return db.query("SELECT " + COLUMNS + " FROM run WHERE started_at >= ? ORDER BY started_at", Runs::view, from);
    }

    /** The latest run of every job, by job id. */
    public Map<String, RunView> latest() {
        return db.query("SELECT " + COLUMNS + " FROM (SELECT run.*, row_number() OVER (PARTITION BY job_id ORDER BY started_at DESC) AS rn"
                + " FROM run) WHERE rn = 1", Runs::view)
            .stream().collect(Collectors.toMap(RunView::jobId, Function.identity()));
    }

    private static RunView view(ResultSet rs) throws SQLException {
        return new RunView(rs.getString("id"), rs.getString("job_id"), rs.getString("kind"), RunState.valueOf(rs.getString("state")),
            Db.instant(rs, "started_at"), Db.instant(rs, "finished_at"), rs.getLong("rows_read"), rs.getLong("upserts"),
            rs.getLong("deletes"), rs.getLong("unchanged"), rs.getString("error"));
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
