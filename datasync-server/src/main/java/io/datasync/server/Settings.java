package io.datasync.server;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Server-wide settings kept in the state store (design 10.2 Settings, 12 Alerts and Retention). */
@Singleton
public class Settings {

    /**
     * Where alerts go and which rules raise them (design 12). {@code failedRuns}: alert when a job has failed this many
     * runs in a row (0 = off). {@code lagMinutes}: alert when an active job has had no successful run for this long
     * (0 = off). {@code webhookUrl} receives {"text": ...}, which Slack and Teams incoming webhooks accept.
     */
    public record AlertSettings(List<String> emails, @Schema(nullable = true) String webhookUrl, int failedRuns, int lagMinutes,
                                boolean deadLetters, boolean deleteGuard) {
        public AlertSettings {
            emails = Objects.requireNonNullElse(emails, List.of());
            webhookUrl = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl.trim();
        }
    }

    /** {@code retentionDays}: finished runs and resolved dead letters older than this are pruned. */
    public record AppSettings(AlertSettings alerts, int retentionDays) {
        public AppSettings {
            alerts = Objects.requireNonNullElse(alerts, DEFAULT_ALERTS);
        }
    }

    private static final AlertSettings DEFAULT_ALERTS = new AlertSettings(List.of(), null, 3, 0, true, true);
    public static final AppSettings DEFAULT = new AppSettings(DEFAULT_ALERTS, 30);

    @Inject
    Db db;

    public AppSettings get() {
        return db.one("SELECT value_json FROM setting WHERE key = 'app'", rs -> Json.read(rs.getString(1), AppSettings.class)).orElse(DEFAULT);
    }

    public void save(AppSettings settings) {
        db.update("INSERT INTO setting (key, value_json, updated_at) VALUES ('app', ?, ?)"
            + " ON CONFLICT (key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at", Json.write(settings), Instant.now());
    }
}
