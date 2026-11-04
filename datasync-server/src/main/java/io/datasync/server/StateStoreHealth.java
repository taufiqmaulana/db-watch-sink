package io.datasync.server;

import io.datasync.core.Errors;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Ready only while the state store answers, at /q/health/ready. */
@Readiness
@Singleton
public class StateStoreHealth implements HealthCheck {

    @Inject
    Db db;

    @Override
    public HealthCheckResponse call() {
        try {
            db.one("SELECT 1", rs -> rs.getInt(1));
            return HealthCheckResponse.up("state-store");
        } catch (RuntimeException e) {
            return HealthCheckResponse.named("state-store").down().withData("error", Errors.describe(e)).build();
        }
    }
}
