package io.datasync.server.api;

import io.datasync.core.JobRunner;
import io.datasync.core.Outbox;
import io.datasync.core.Outbox.SinkStatus;
import io.datasync.core.Preview;
import io.datasync.core.RunKind;
import io.datasync.core.config.DataSyncConfig.JobDef;
import io.datasync.server.Audit;
import io.datasync.server.Db;
import io.datasync.server.Events;
import io.datasync.server.JobConfigs;
import io.datasync.server.JobSchedule;
import io.datasync.server.Json;
import io.datasync.server.Runs;
import io.datasync.server.Runs.RunView;
import io.datasync.server.Scheduler;
import io.datasync.server.SinkWorkers;
import io.datasync.server.api.ConnectionResource.ConnectionRole;
import io.datasync.server.auth.Requires;
import io.datasync.server.auth.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Jobs are stored in the shape of the YAML config, with connections referenced by id. */
@Path("/jobs")
public class JobResource {

    public enum Status { ACTIVE, PAUSED, NEEDS_REVIEW }

    /**
     * {@code nextRunAt} and {@code nextReconcileAt} (HYBRID only) are set only while the job is ACTIVE.
     * {@code watermarkValue} is the highest watermark delivered so far, for WATERMARK and HYBRID jobs.
     * {@code periodSeconds} is the time between scheduled runs, for warning when runs take longer than half of it.
     * {@code sinks} is each sink's delivery state, in config order.
     */
    public record JobView(String id, String name, Status status, int version, boolean running, @Schema(nullable = true) RunView lastRun,
                          @Schema(nullable = true) Instant nextRunAt, @Schema(nullable = true) Instant nextReconcileAt,
                          @Schema(nullable = true) String watermarkValue, long periodSeconds, JobDef config, List<SinkView> sinks,
                          Instant createdAt, Instant updatedAt) {}

    /**
     * A sink's delivery from the job outbox (design 6). {@code backlog}: batches waiting for it. {@code attempts}: failed
     * attempts at the next one, retried at {@code nextRetryAt}. {@code loaded}: it has had its full load (or has it queued).
     */
    public record SinkView(String id, String label, SinkStatus status, long backlog, int attempts, @Schema(nullable = true) Instant nextRetryAt,
                           @Schema(nullable = true) String lastError, @Schema(nullable = true) Instant deliveredAt, boolean delivering,
                           boolean loaded) {}

    /** {@code version} is required on update and must match the stored one (optimistic locking). */
    public record JobInput(JobDef config, Integer version) {}

    private static final String SELECT = "SELECT id, name, status, version, config_json, created_at, updated_at FROM job";
    private static final Set<String> DELETE_MODES = Set.of("SOFT", "HARD", "IGNORE");
    private static final Set<String> DELETE_AS = Set.of("delete", "upsert-with-flag");
    private static final Set<String> BATCH_MODES = Set.of("per-batch", "per-row");
    private static final Set<String> ON_DEAD_LETTER = Set.of("PAUSE", "SKIP");
    private static final int PREVIEW_ROWS = 10;

    @Inject
    Db db;

    @Inject
    JobConfigs configs;

    @Inject
    Scheduler scheduler;

    @Inject
    SinkWorkers workers;

    @Inject
    Runs runs;

    @Inject
    Events events;

    @Inject
    Audit audit;

    @GET
    public List<JobView> list() {
        Map<String, RunView> latest = runs.latest();
        Map<String, String> cursors = cursors();
        return db.query(SELECT + " ORDER BY name", rs -> view(rs, latest, cursors));
    }

    @GET
    @Path("/{id}")
    public JobView get(String id) {
        Map<String, RunView> latest = runs.latest();
        Map<String, String> cursors = cursors();
        return db.one(SELECT + " WHERE id = ?", rs -> view(rs, latest, cursors), id).orElseThrow(() -> new NotFoundException("Job not found"));
    }

    @POST
    public RestResponse<JobView> create(JobInput in) {
        JobDef def = validate(in == null ? null : in.config(), null);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        db.update("INSERT INTO job (id, name, config_json, status, version, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', 1, ?, ?)",
            id, def.name(), Json.write(def), now, now);
        changed("job.create", id, def.name());
        return RestResponse.status(RestResponse.Status.CREATED, get(id));
    }

    @PUT
    @Path("/{id}")
    public JobView update(String id, JobInput in) {
        get(id);
        JobDef def = validate(in == null ? null : in.config(), id);
        if (in.version() == null) throw new IllegalArgumentException("Version is required");
        int updated = db.update("UPDATE job SET name = ?, config_json = ?, version = version + 1, updated_at = ? WHERE id = ? AND version = ?",
            def.name(), Json.write(def), Instant.now(), id, in.version());
        if (updated == 0) throw ApiErrors.conflict("This job was changed by someone else. Reload it and try again.");
        changed("job.update", id, def.name());
        return get(id);
    }

    /** Also removes the job's hash index, run history and dead letters. Target tables are left alone. */
    @DELETE
    @Path("/{id}")
    public void delete(String id) {
        JobView job = get(id);
        if (runs.isRunning(id)) throw ApiErrors.conflict("Wait for the current run to finish");
        db.tx(c -> {
            Db.update(c, "DELETE FROM row_index WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM sink_load WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM job_cursor WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM outbox_batch WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM sink_offset WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM run WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM dead_letter WHERE job_id = ?", id);
            return Db.update(c, "DELETE FROM job WHERE id = ?", id);
        });
        changed("job.delete", id, job.name());
    }

    @POST
    @Path("/{id}/pause")
    @Requires(Role.OPERATOR)
    public JobView pause(String id) {
        return setStatus(id, Status.PAUSED, "job.pause");
    }

    @POST
    @Path("/{id}/resume")
    @Requires(Role.OPERATOR)
    public JobView resume(String id) {
        return setStatus(id, Status.ACTIVE, "job.resume");
    }

    /** Starts a run in the background. {@code allowDeletes} skips the delete guard after a review. */
    @POST
    @Path("/{id}/run")
    @Requires(Role.OPERATOR)
    public RestResponse<JobView> run(String id, @RestQuery boolean allowDeletes) {
        return start(id, RunKind.POLL, allowDeletes);
    }

    /**
     * Starts a full snapshot diff in the background (design 5.4). For WATERMARK and HYBRID jobs it is the only
     * run that applies deletes; for SNAPSHOT_DIFF jobs it is the same as a regular run.
     */
    @POST
    @Path("/{id}/reconcile")
    @Requires(Role.OPERATOR)
    public RestResponse<JobView> reconcile(String id, @RestQuery boolean allowDeletes) {
        return start(id, RunKind.RECONCILE, allowDeletes);
    }

    /** Continues delivery to a sink paused after a dead letter, from the batch after it. */
    @POST
    @Path("/{id}/resume-sink")
    @Requires(Role.OPERATOR)
    public JobView resumeSink(String id, @RestQuery String sink) {
        SinkView view = sink(id, sink);
        if (view.status() != SinkStatus.PAUSED) throw ApiErrors.conflict("This sink is not paused");
        outbox(o -> o.resume(id, sink));
        audit.record("sink.resume", "job:" + id, Map.of("sink", view.label()));
        workers.kick(id);
        events.publish(new Events.Event("job-changed", id, null, null));
        return get(id);
    }

    /**
     * Drops the sink's backlog and starts a run that sends it every row (design 6), e.g. after it fell too far behind or
     * its target was emptied. Other sinks only get the run's changes.
     */
    @POST
    @Path("/{id}/resnapshot")
    @Requires(Role.OPERATOR)
    public RestResponse<JobView> resnapshot(String id, @RestQuery String sink) {
        SinkView view = sink(id, sink);
        if (runs.isRunning(id)) throw ApiErrors.conflict("Wait for the current run to finish");
        outbox(o -> o.resnapshot(id, sink));
        audit.record("sink.resnapshot", "job:" + id, Map.of("sink", view.label()));
        runs.start(id, RunKind.POLL, false);
        return RestResponse.status(RestResponse.Status.ACCEPTED, get(id));
    }

    private SinkView sink(String jobId, String sinkId) {
        return get(jobId).sinks().stream().filter(s -> s.id().equals(sinkId)).findFirst()
            .orElseThrow(() -> new NotFoundException("This job has no such sink"));
    }

    private interface OutboxAction {
        void apply(Outbox outbox) throws SQLException;
    }

    private void outbox(OutboxAction action) {
        try {
            action.apply(new Outbox(db.store()));
        } catch (SQLException e) {
            throw new IllegalStateException("state store error", e);
        }
    }

    /** Each configured sink's delivery state; a sink that has not been through a run yet shows as active and empty. */
    private List<SinkView> sinkViews(String jobId, JobDef config) {
        record Offset(String status, long backlog, int attempts, Instant retry, String error, Instant delivered) {}
        Map<String, Offset> offsets = db.query("SELECT o.sink_id, o.status, o.attempts, o.next_retry_at, o.last_error, o.delivered_at,"
                + " (SELECT count(*) FROM outbox_batch b WHERE b.job_id = o.job_id AND b.batch_id > o.last_acked_batch"
                + " AND instr(b.recipients, '|' || o.sink_id || '|') > 0) AS backlog FROM sink_offset o WHERE o.job_id = ?",
            rs -> Map.entry(rs.getString("sink_id"), new Offset(rs.getString("status"), rs.getLong("backlog"), rs.getInt("attempts"),
                Db.instant(rs, "next_retry_at"), rs.getString("last_error"), Db.instant(rs, "delivered_at"))), jobId)
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<String> loaded = Set.copyOf(db.query("SELECT sink_id FROM sink_load WHERE job_id = ?", rs -> rs.getString(1), jobId));
        return config.sinks().stream().map(cfg -> {
            String sinkId = JobRunner.sinkId(cfg);
            Offset o = offsets.getOrDefault(sinkId, new Offset("ACTIVE", 0, 0, null, null, null));
            return new SinkView(sinkId, configs.sinkLabel(sinkId), SinkStatus.valueOf(o.status()), o.backlog(), o.attempts(), o.retry(),
                o.error(), o.delivered(), workers.isDelivering(jobId, sinkId), loaded.contains(sinkId));
        }).toList();
    }

    private RestResponse<JobView> start(String id, RunKind kind, boolean allowDeletes) {
        JobView job = get(id);
        if (runs.start(id, kind, allowDeletes) == null) throw ApiErrors.conflict("This job is already running");
        audit.record(kind == RunKind.POLL ? "job.run" : "job.reconcile", "job:" + id, Map.of("name", job.name(), "allowDeletes", allowDeletes));
        return RestResponse.status(RestResponse.Status.ACCEPTED, get(id));
    }

    /** Dry run: checks the config, maps the columns and reads sample rows. Nothing is written. */
    @POST
    @Path("/validate")
    public Preview validate(JobDef config) {
        JobDef def = validate(config, null, false);
        return new JobRunner(configs.engineConfig("preview", def), db.store()).preview("preview", PREVIEW_ROWS);
    }

    @GET
    @Path("/{id}/runs")
    public List<RunView> runs(String id, @RestQuery @DefaultValue("50") int limit) {
        get(id);
        return runs.forJob(id, Math.min(limit, 500));
    }

    private JobView setStatus(String id, Status status, String action) {
        JobView job = get(id);
        db.update("UPDATE job SET status = ?, updated_at = ? WHERE id = ?", status, Instant.now(), id);
        changed(action, id, job.name());
        return get(id);
    }

    private void changed(String action, String id, String name) {
        audit.record(action, "job:" + id, Map.of("name", name));
        scheduler.reschedule(id);
        events.publish(new Events.Event("job-changed", id, null, null));
    }

    JobDef validate(JobDef def, String selfId) {
        return validate(def, selfId, true);
    }

    /** Checks the definition and returns it with a trimmed name. */
    private JobDef validate(JobDef def, String selfId, boolean uniqueName) {
        if (def == null) throw new IllegalArgumentException("Job configuration is required");
        String name = ApiErrors.required(def.name(), "Name");
        if (uniqueName && db.one("SELECT id FROM job WHERE name = ?", rs -> rs.getString(1), name).filter(id -> !id.equals(selfId)).isPresent()) {
            throw ApiErrors.conflict("A job named " + name + " already exists");
        }
        if (def.source() == null) throw new IllegalArgumentException("Choose a source");
        if (connection(ApiErrors.required(def.source().connection(), "Source connection")).role() == ConnectionRole.SINK) {
            throw new IllegalArgumentException("The source connection is a sink-only connection");
        }
        ApiErrors.required(def.source().object(), "Source table or view");
        ApiErrors.range(def.batchSize(), 1, 100_000, "Batch size");
        ApiErrors.range(def.fetchSize(), 1, 100_000, "Fetch size");
        ApiErrors.range(def.maxDeletePercent(), 0, 100, "Delete guard");
        ApiErrors.range(def.queryTimeoutSeconds(), 1, 86_400, "Query timeout");
        if (def.incremental()) {
            ApiErrors.required(def.watermark().column(), "Watermark column");
            def.watermark().overlapDuration();   // the column itself is checked against the source by the dry run
        }
        JobSchedule.of(def);
        JobSchedule.reconcile(def);
        if (def.sinks().isEmpty()) throw new IllegalArgumentException("Add at least one sink");
        if (def.sinks().stream().map(JobRunner::sinkId).distinct().count() < def.sinks().size()) {
            throw new IllegalArgumentException("Two sinks write to the same target");
        }
        for (Map<String, Object> sink : def.sinks()) validateSink(sink);
        return def.withName(name);
    }

    /** A database sink writes to a target table on a database connection; a webhook sink posts to a webhook connection. */
    private void validateSink(Map<String, Object> sink) {
        String type = String.valueOf(sink.get("type"));
        if (!Set.of("jdbc", "webhook").contains(type)) throw new IllegalArgumentException("Unknown sink type " + type);
        Object conn = sink.get("connection");
        Conn connection = connection(ApiErrors.required(conn == null ? null : conn.toString(), "Sink connection"));
        if (connection.role() == ConnectionRole.SOURCE) throw new IllegalArgumentException("A sink uses a source-only connection");
        boolean webhook = type.equals("webhook");
        if (webhook != connection.kind().equals("webhook")) {
            throw new IllegalArgumentException(webhook ? "A webhook sink needs a webhook connection" : "A database sink needs a database connection");
        }
        if (!webhook) {
            ApiErrors.required(text(sink.get("target")), "Sink target table");
            oneOf(String.valueOf(sink.getOrDefault("deleteMode", "SOFT")).toUpperCase(), DELETE_MODES, "delete mode");
        } else {
            oneOf(String.valueOf(sink.getOrDefault("deleteAs", "delete")), DELETE_AS, "delete handling");
            if (sink.get("batch") instanceof Map<?, ?> batch) {
                if (batch.get("mode") != null) oneOf(batch.get("mode").toString(), BATCH_MODES, "batch mode");
                if (batch.get("maxRows") != null) ApiErrors.range(number(batch.get("maxRows"), "Rows per request"), 1, 10_000, "Rows per request");
            }
            if (sink.get("timeoutSeconds") != null) ApiErrors.range(number(sink.get("timeoutSeconds"), "Timeout"), 1, 300, "Timeout");
        }
        oneOf(String.valueOf(sink.getOrDefault("onDeadLetter", "PAUSE")).toUpperCase(), ON_DEAD_LETTER, "dead letter handling");
        if (sink.get("retry") instanceof Map<?, ?> retry && retry.get("maxAttempts") != null) {
            ApiErrors.range(number(retry.get("maxAttempts"), "Attempts"), 1, 20, "Attempts");
        }
    }

    private static void oneOf(String value, Set<String> allowed, String what) {
        if (!allowed.contains(value)) throw new IllegalArgumentException("Unknown " + what + " " + value);
    }

    private static int number(Object value, String what) {
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number");
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private record Conn(String kind, ConnectionRole role) {}

    private Conn connection(String connectionId) {
        return db.one("SELECT kind, role FROM connection WHERE id = ?", rs -> new Conn(rs.getString(1), ConnectionRole.valueOf(rs.getString(2))),
                connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Connection " + connectionId + " does not exist"));
    }

    /** Each job's watermark cursor, keyed by job id, as "column" + NUL + "value". */
    private Map<String, String> cursors() {
        return db.query("SELECT job_id, watermark_column, watermark_value FROM job_cursor",
                rs -> Map.entry(rs.getString(1), rs.getString(2) + "\0" + rs.getString(3)))
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private JobView view(ResultSet rs, Map<String, RunView> latest, Map<String, String> cursors) throws SQLException {
        String id = rs.getString("id");
        Status status = Status.valueOf(rs.getString("status"));
        JobDef config = Json.read(rs.getString("config_json"), JobDef.class);
        boolean active = status == Status.ACTIVE;
        // a cursor saved for another column than the current one is ignored by the next run
        String cursor = cursors.get(id);
        String watermark = cursor != null && config.incremental() && cursor.startsWith(config.watermark().column() + "\0")
            ? cursor.substring(cursor.indexOf('\0') + 1) : null;
        return new JobView(id, rs.getString("name"), status, rs.getInt("version"), runs.isRunning(id), latest.get(id),
            active ? scheduler.next(id, RunKind.POLL).orElse(null) : null,
            active ? scheduler.next(id, RunKind.RECONCILE).orElse(null) : null,
            watermark, JobSchedule.of(config).period().toSeconds(), config, sinkViews(id, config), Db.instant(rs, "created_at"),
            Db.instant(rs, "updated_at"));
    }
}
