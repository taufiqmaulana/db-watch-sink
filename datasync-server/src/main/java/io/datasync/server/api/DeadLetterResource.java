package io.datasync.server.api;

import io.datasync.core.Errors;
import io.datasync.core.JobRunner;
import io.datasync.server.Audit;
import io.datasync.server.Db;
import io.datasync.server.Events;
import io.datasync.server.JobConfigs;
import io.datasync.server.Json;
import io.datasync.server.auth.Requires;
import io.datasync.server.auth.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.resteasy.reactive.RestQuery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batches a sink could not take after its retries (design 6). Retrying replays a batch once to the sink it failed
 * on, under the job's current config; discarding drops it. Either resolves it, and both are audited.
 */
@Path("/dead-letters")
public class DeadLetterResource {

    public enum Resolution { RETRIED, DISCARDED }

    public enum Filter { OPEN, RESOLVED, ALL }

    public record DeadLetterView(String id, String jobId, @Schema(nullable = true) String jobName, String sinkId, long batchId,
                                 int changeCount, @Schema(nullable = true) String error, int attempts, Instant createdAt,
                                 @Schema(nullable = true) Instant resolvedAt, @Schema(nullable = true) Resolution resolution) {}

    /** {@code payload} is the stored batch: its target schema and changes. */
    public record DeadLetterDetail(DeadLetterView deadLetter, Map<String, Object> payload) {}

    public record Selection(List<String> ids) {}

    /** {@code failed} maps dead letter ids to why the sink still refused them. */
    public record BulkResult(int resolved, Map<String, String> failed) {}

    private static final String SELECT = "SELECT d.id, d.job_id, j.name AS job_name, d.sink_id, d.batch_id, d.change_count, d.error,"
        + " d.attempts, d.created_at, d.resolved_at, d.resolution FROM dead_letter d LEFT JOIN job j ON j.id = d.job_id";

    @Inject
    Db db;

    @Inject
    JobConfigs configs;

    @Inject
    Events events;

    @Inject
    Audit audit;

    /** Newest first. */
    @GET
    public List<DeadLetterView> list(@RestQuery String jobId, @RestQuery @DefaultValue("OPEN") Filter status,
                                     @RestQuery @DefaultValue("200") int limit) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (jobId != null && !jobId.isBlank()) {
            where.add("d.job_id = ?");
            args.add(jobId);
        }
        if (status == Filter.OPEN) where.add("d.resolved_at IS NULL");
        if (status == Filter.RESOLVED) where.add("d.resolved_at IS NOT NULL");
        args.add(Math.min(limit, 1000));
        String sql = SELECT + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY d.created_at DESC LIMIT ?";
        return db.query(sql, DeadLetterResource::view, args.toArray());
    }

    @GET
    @Path("/{id}")
    public DeadLetterDetail get(String id) {
        DeadLetterView view = view(id);
        String payload = db.one("SELECT payload FROM dead_letter WHERE id = ?", rs -> rs.getString(1), id).orElseThrow();
        return new DeadLetterDetail(view, Json.map(payload));
    }

    /** Replays each open dead letter once, without retries; one that fails again stays open with the new error. */
    @POST
    @Path("/retry")
    @Requires(Role.OPERATOR)
    public BulkResult retry(Selection selection) {
        int resolved = 0;
        Map<String, String> failed = new LinkedHashMap<>();
        for (String id : ids(selection)) {
            DeadLetterView dl = view(id);
            if (dl.resolvedAt() != null) continue;
            String payload = db.one("SELECT payload FROM dead_letter WHERE id = ?", rs -> rs.getString(1), id).orElseThrow();
            try {
                new JobRunner(configs.engineConfig(dl.jobId(), configs.job(dl.jobId())), db.store()).redeliver(dl.jobId(), dl.sinkId(), payload);
                resolve(dl, Resolution.RETRIED);
                resolved++;
            } catch (RuntimeException e) {
                String error = Errors.describe(e);
                db.update("UPDATE dead_letter SET error = ?, attempts = attempts + 1 WHERE id = ?", error, id);
                failed.put(id, error);
            }
        }
        return new BulkResult(resolved, failed);
    }

    @POST
    @Path("/discard")
    @Requires(Role.OPERATOR)
    public BulkResult discard(Selection selection) {
        int resolved = 0;
        for (String id : ids(selection)) {
            DeadLetterView dl = view(id);
            if (dl.resolvedAt() != null) continue;
            resolve(dl, Resolution.DISCARDED);
            resolved++;
        }
        return new BulkResult(resolved, Map.of());
    }

    private void resolve(DeadLetterView dl, Resolution resolution) {
        db.update("UPDATE dead_letter SET resolved_at = ?, resolution = ? WHERE id = ? AND resolved_at IS NULL", Instant.now(), resolution, dl.id());
        audit.record(resolution == Resolution.RETRIED ? "dead-letter.retry" : "dead-letter.discard", "job:" + dl.jobId(),
            Map.of("deadLetter", dl.id(), "sink", dl.sinkId(), "changes", dl.changeCount()));
        events.publish(new Events.Event("dead-letter-resolved", dl.jobId(), null, resolution.name()));
    }

    private static List<String> ids(Selection selection) {
        if (selection == null || selection.ids() == null || selection.ids().isEmpty()) throw new IllegalArgumentException("Select dead letters");
        return selection.ids();
    }

    private DeadLetterView view(String id) {
        return db.one(SELECT + " WHERE d.id = ?", DeadLetterResource::view, id).orElseThrow(() -> new NotFoundException("Dead letter not found"));
    }

    private static DeadLetterView view(ResultSet rs) throws SQLException {
        String resolution = rs.getString("resolution");
        return new DeadLetterView(rs.getString("id"), rs.getString("job_id"), rs.getString("job_name"), rs.getString("sink_id"),
            rs.getLong("batch_id"), rs.getInt("change_count"), rs.getString("error"), rs.getInt("attempts"), Db.instant(rs, "created_at"),
            Db.instant(rs, "resolved_at"), resolution == null ? null : Resolution.valueOf(resolution));
    }
}
