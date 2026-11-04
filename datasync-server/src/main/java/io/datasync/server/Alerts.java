package io.datasync.server;

import io.datasync.core.DeliveryResult;
import io.datasync.core.Errors;
import io.datasync.core.Outbox.SinkStatus;
import io.datasync.core.RunResult;
import io.datasync.core.RunState;
import io.datasync.server.Housekeeping.Freshness;
import io.datasync.server.Settings.AlertSettings;
import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in alert rules (design 12): a job failed N runs in a row, an active job lags, a sink dead-lettered a batch or fell
 * too far behind (design 6), the delete guard stopped a run. Alerts go to the configured email recipients (SMTP from the QUARKUS_MAILER_* settings) and webhook,
 * and are recorded in the audit log. A failing channel is logged, never thrown.
 */
@Singleton
public class Alerts {

    public enum Event { JOB_FAILED, JOB_LAGGING, DEAD_LETTER, SINK_BEHIND, DELETE_GUARD, TEST }

    /** {@code failures} maps a channel to why it could not deliver. */
    public record Delivery(List<String> sent, Map<String, String> failures) {}

    @Inject
    Settings settings;

    @Inject
    Mailer mailer;

    @Inject
    Db db;

    @Inject
    Audit audit;

    @Inject
    JobConfigs configs;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Set<String> lagging = ConcurrentHashMap.newKeySet();

    /** Checks the run-based rules for a finished run of the job {@code jobId}, named {@code jobName}. */
    public void afterRun(String jobId, String jobName, RunResult result) {
        AlertSettings rules = settings.get().alerts();
        if (result.state() == RunState.NEEDS_REVIEW && rules.deleteGuard()) {
            send(rules, Event.DELETE_GUARD, jobId, "Job " + jobName + " stopped before deleting: " + result.error()
                + ". Check the source, then apply the deletes on the job page if they are real.");
        }
        if (!result.laggingSinks().isEmpty() && rules.deadLetters()) {
            send(rules, Event.SINK_BEHIND, jobId, "Job " + jobName + ": " + result.laggingSinks().stream().map(configs::sinkLabel).toList()
                + " fell too far behind and need a resnapshot to be loaded again.");
        }
        if (result.state() == RunState.FAILED && rules.failedRuns() > 0 && justReached(jobId, rules.failedRuns())) {
            send(rules, Event.JOB_FAILED, jobId, "Job " + jobName + " failed " + rules.failedRuns() + " run" + (rules.failedRuns() == 1 ? "" : "s")
                + " in a row. Last error: " + result.error());
        }
    }

    /** Checks the dead-letter rule after a sink's delivery pass. */
    public void afterDelivery(String jobId, String jobName, DeliveryResult result) {
        AlertSettings rules = settings.get().alerts();
        long n = result.deadLetters();
        if (n == 0 || !rules.deadLetters()) return;
        send(rules, Event.DEAD_LETTER, jobId, "Job " + jobName + ", sink " + configs.sinkLabel(result.sinkId()) + ": moved " + n + " batch"
            + (n == 1 ? "" : "es") + " to the dead letters" + (result.status() == SinkStatus.PAUSED ? " and paused the sink" : "") + ". "
            + result.error());
    }

    /** Alerts once when an active job goes longer than the threshold without a successful run, again only after it recovers. */
    public void checkLag(List<Freshness> jobs) {
        AlertSettings rules = settings.get().alerts();
        Duration limit = Duration.ofMinutes(rules.lagMinutes());
        Instant now = Instant.now();
        for (Freshness job : jobs) {
            boolean late = rules.lagMinutes() > 0 && job.active() && Duration.between(job.lastSuccess(), now).compareTo(limit) > 0;
            if (!late) {
                lagging.remove(job.id());
            } else if (lagging.add(job.id())) {
                send(rules, Event.JOB_LAGGING, job.id(), "Job " + job.name() + " has had no successful run for over "
                    + rules.lagMinutes() + " minutes (last at " + job.lastSuccess() + ").");
            }
        }
    }

    /** Sends a test alert to the given channels, for checking them before saving. */
    public Delivery test(AlertSettings channels) {
        return send(channels, Event.TEST, null, "Test alert from DataSync. Alerts will arrive like this.");
    }

    /** True when the job's latest {@code n} runs failed and the one before did not, so the alert fires once per streak. */
    private boolean justReached(String jobId, int n) {
        List<String> states = db.query("SELECT state FROM run WHERE job_id = ? AND state <> 'RUNNING' ORDER BY started_at DESC LIMIT ?",
            rs -> rs.getString(1), jobId, n + 1);
        return states.size() >= n && states.subList(0, n).stream().allMatch("FAILED"::equals)
            && (states.size() == n || !"FAILED".equals(states.get(n)));
    }

    private Delivery send(AlertSettings channels, Event event, String jobId, String message) {
        List<String> sent = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        if (!channels.emails().isEmpty()) {
            try {
                mailer.send(new Mail().setTo(channels.emails()).setSubject("[DataSync] " + title(event)).setText(message));
                sent.add("email");
            } catch (RuntimeException e) {
                failures.put("email", Errors.describe(e));
            }
        }
        if (channels.webhookUrl() != null) {
            try {
                String body = Json.write(Map.of("text", "[DataSync] " + message, "event", event.name(), "jobId", jobId == null ? "" : jobId,
                    "at", Instant.now().toString()));
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(channels.webhookUrl())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 == 2) sent.add("webhook");
                else failures.put("webhook", "HTTP " + response.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.put("webhook", "interrupted");
            } catch (Exception e) {
                failures.put("webhook", Errors.describe(e));
            }
        }
        failures.forEach((channel, error) -> Log.warnf("alert %s could not be sent by %s: %s", event, channel, error));
        if (event != Event.TEST && (!sent.isEmpty() || !failures.isEmpty())) {
            audit.record("system", "alert." + event.name().toLowerCase().replace('_', '-'), jobId == null ? "alerts" : "job:" + jobId,
                Map.of("message", message, "sent", sent, "failed", failures.keySet()));
        }
        return new Delivery(sent, failures);
    }

    private static String title(Event event) {
        return switch (event) {
            case JOB_FAILED -> "Job failing";
            case JOB_LAGGING -> "Job lagging";
            case DEAD_LETTER -> "Batches dead-lettered";
            case SINK_BEHIND -> "Sink needs a resnapshot";
            case DELETE_GUARD -> "Delete guard tripped";
            case TEST -> "Test alert";
        };
    }
}
