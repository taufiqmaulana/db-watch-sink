package io.datasync.server.api;

import io.datasync.core.RunState;
import io.datasync.server.Audit;
import io.datasync.server.Db;
import io.datasync.server.Events;
import io.datasync.server.Runs;
import io.datasync.server.Runs.RunView;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Path("/")
public class MonitoringResource {

    /** Counts for the dashboard tiles; the 24 h figures cover runs started in the last 24 hours. */
    public record Overview(int jobs, int active, int paused, int needsReview, int failing, int running,
                           int runs24h, int failedRuns24h, long rowsChanged24h) {}

    @Inject
    Db db;

    @Inject
    Runs runs;

    @Inject
    Events events;

    @Inject
    Audit audit;

    @GET
    @Path("/overview")
    public Overview overview() {
        Map<String, RunView> latest = runs.latest();
        List<String[]> jobs = db.query("SELECT id, status FROM job", rs -> new String[] {rs.getString(1), rs.getString(2)});
        List<RunView> recent = runs.since(Instant.now().minus(Duration.ofHours(24)));
        return new Overview(
            jobs.size(),
            (int) jobs.stream().filter(j -> j[1].equals("ACTIVE")).count(),
            (int) jobs.stream().filter(j -> j[1].equals("PAUSED")).count(),
            (int) jobs.stream().filter(j -> j[1].equals("NEEDS_REVIEW")).count(),
            (int) jobs.stream().filter(j -> latest.containsKey(j[0]) && latest.get(j[0]).state() == RunState.FAILED).count(),
            (int) jobs.stream().filter(j -> runs.isRunning(j[0])).count(),
            recent.size(),
            (int) recent.stream().filter(r -> r.state() == RunState.FAILED).count(),
            recent.stream().mapToLong(r -> r.upserts() + r.deletes()).sum());
    }

    @GET
    @Path("/runs/{id}")
    public RunView run(String id) {
        return runs.get(id).orElseThrow(() -> new NotFoundException("Run not found"));
    }

    /** Live run and job changes, for the UI to refresh what it shows. */
    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<Events.Event> events() {
        return events.stream();
    }

    /** Newest first. {@code entity} narrows to one object, e.g. job:&lt;id&gt;. */
    @GET
    @Path("/audit")
    public List<Audit.Entry> audit(@RestQuery String entity, @RestQuery @DefaultValue("200") int limit) {
        return audit.list(entity == null || entity.isBlank() ? null : entity, Math.min(limit, 1000));
    }
}
