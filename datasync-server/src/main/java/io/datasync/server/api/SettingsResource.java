package io.datasync.server.api;

import io.datasync.server.Alerts;
import io.datasync.server.Audit;
import io.datasync.server.Settings;
import io.datasync.server.Settings.AlertSettings;
import io.datasync.server.Settings.AppSettings;
import io.datasync.server.auth.Requires;
import io.datasync.server.auth.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

import java.util.Map;

/** Alert channels and rules, and retention (design 10.2 Settings). Admin only: the alert webhook URL may hold a token. */
@Path("/settings")
public class SettingsResource {

    @Inject
    Settings settings;

    @Inject
    Alerts alerts;

    @Inject
    Audit audit;

    @GET
    @Requires(Role.ADMIN)
    public AppSettings get() {
        return settings.get();
    }

    @PUT
    public AppSettings update(AppSettings in) {
        AppSettings checked = validate(in);
        settings.save(checked);
        audit.record("settings.update", "settings", Map.of("emails", checked.alerts().emails().size(),
            "webhook", checked.alerts().webhookUrl() != null, "retentionDays", checked.retentionDays()));
        return settings.get();
    }

    /** Sends a test alert to the given channels, which need not be saved yet. */
    @POST
    @Path("/alerts/test")
    public Alerts.Delivery testAlerts(AlertSettings channels) {
        AlertSettings checked = validate(new AppSettings(channels, Settings.DEFAULT.retentionDays())).alerts();
        if (checked.emails().isEmpty() && checked.webhookUrl() == null) throw new IllegalArgumentException("Add an email address or a webhook URL first");
        return alerts.test(checked);
    }

    private static AppSettings validate(AppSettings in) {
        if (in == null || in.alerts() == null) throw new IllegalArgumentException("Settings are required");
        AlertSettings a = in.alerts();
        var emails = a.emails().stream().map(String::trim).filter(e -> !e.isEmpty()).distinct().toList();
        for (String email : emails) {
            if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("Not an email address: " + email);
        }
        if (a.webhookUrl() != null && !a.webhookUrl().matches("(?i)https?://.+")) {
            throw new IllegalArgumentException("The alert webhook URL must start with http:// or https://");
        }
        ApiErrors.range(a.failedRuns(), 0, 100, "Failed runs in a row");
        ApiErrors.range(a.lagMinutes(), 0, 10_080, "Lag minutes");
        ApiErrors.range(in.retentionDays(), 1, 3650, "Retention days");
        return new AppSettings(new AlertSettings(emails, a.webhookUrl(), a.failedRuns(), a.lagMinutes(), a.deadLetters(), a.deleteGuard()),
            in.retentionDays());
    }
}
