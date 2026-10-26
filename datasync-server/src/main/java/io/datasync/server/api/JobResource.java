package io.datasync.server.api;

import io.datasync.core.JobRunner;
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
     */
    public record JobView(String id, String name, Status status, int version, boolean running, @Schema(nullable = true) RunView lastRun,
                          @Schema(nullable = true) Instant nextRunAt, @Schema(nullable = true) Instant nextReconcileAt,
                          @Schema(nullable = true) String watermarkValue, JobDef config, Instant createdAt, Instant updatedAt) {}

    /** {@code version} is required on update and must match the stored one (optimistic locking). */
    public record JobInput(JobDef config, Integer version) {}

    private static final String SELECT = "SELECT id, name, status, version, config_json, created_at, updated_at FROM job";
    private static final Set<String> DELETE_MODES = Set.of("SOFT", "HARD", "IGNORE");
    private static final int PREVIEW_ROWS = 10;

    @Inject
    Db db;

    @Inject
    JobConfigs configs;

    @Inject
    Scheduler scheduler;

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

    /** Also removes the job's hash index and run history. Target tables are left alone. */
    @DELETE
    @Path("/{id}")
    public void delete(String id) {
        JobView job = get(id);
        if (runs.isRunning(id)) throw ApiErrors.conflict("Wait for the current run to finish");
        db.tx(c -> {
            Db.update(c, "DELETE FROM row_index WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM sink_load WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM job_cursor WHERE job_id = ?", id);
            Db.update(c, "DELETE FROM run WHERE job_id = ?", id);
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

    private JobDef validate(JobDef def, String selfId) {
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
        if (role(ApiErrors.required(def.source().connection(), "Source connection")) == ConnectionRole.SINK) {
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
            throw new IllegalArgumentException("Two sinks write to the same target table");
        }
        for (Map<String, Object> sink : def.sinks()) {
            if (!"jdbc".equals(sink.get("type"))) throw new IllegalArgumentException("Only database sinks are supported so far");
            Object conn = sink.get("connection");
            if (role(ApiErrors.required(conn == null ? null : conn.toString(), "Sink connection")) == ConnectionRole.SOURCE) {
                throw new IllegalArgumentException("A sink uses a source-only connection");
            }
            Object target = sink.get("target");
            ApiErrors.required(target == null ? null : target.toString(), "Sink target table");
            Object deleteMode = sink.getOrDefault("deleteMode", "SOFT");
            if (!DELETE_MODES.contains(String.valueOf(deleteMode).toUpperCase())) throw new IllegalArgumentException("Unknown delete mode " + deleteMode);
        }
        return def.withName(name);
    }

    private ConnectionRole role(String connectionId) {
        return db.one("SELECT role FROM connection WHERE id = ?", rs -> ConnectionRole.valueOf(rs.getString(1)), connectionId)
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
            watermark, config, Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }
}
