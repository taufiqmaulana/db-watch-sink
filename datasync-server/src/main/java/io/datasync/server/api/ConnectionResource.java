package io.datasync.server.api;

import io.datasync.api.ObjectRef;
import io.datasync.api.ObjectSchema;
import io.datasync.api.SourceConnector;
import io.datasync.core.Connectors;
import io.datasync.core.Errors;
import io.datasync.core.config.DataSyncConfig.JobDef;
import io.datasync.server.Audit;
import io.datasync.server.Db;
import io.datasync.server.JobConfigs;
import io.datasync.server.Json;
import io.datasync.server.Secrets;
import io.datasync.server.auth.Requires;
import io.datasync.server.auth.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Connections to source and sink databases. Passwords are write-only: accepted, stored encrypted, never returned. */
@Path("/connections")
public class ConnectionResource {

    public enum ConnectionRole { SOURCE, SINK, BOTH }

    public record ConnectionView(String id, String name, String kind, ConnectionRole role, String url, @Schema(nullable = true) String username,
                                 int maxConcurrency, boolean hasPassword, Instant createdAt, Instant updatedAt) {}

    /** On update, an empty password keeps the stored one. */
    public record ConnectionInput(String name, String kind, ConnectionRole role, String url, @Schema(nullable = true) String username,
                                  String password, Integer maxConcurrency) {}

    public record TestResult(boolean ok, String message, @Schema(nullable = true) String detail) {}

    private static final String SELECT = "SELECT id, name, kind, role, config_json, secret_enc IS NOT NULL AS has_password,"
        + " max_concurrency, created_at, updated_at FROM connection";

    @Inject
    Db db;

    @Inject
    Secrets secrets;

    @Inject
    JobConfigs configs;

    @Inject
    Audit audit;

    @GET
    public List<ConnectionView> list() {
        return db.query(SELECT + " ORDER BY name", ConnectionResource::view);
    }

    @GET
    @Path("/{id}")
    public ConnectionView get(String id) {
        return db.one(SELECT + " WHERE id = ?", ConnectionResource::view, id).orElseThrow(() -> new NotFoundException("Connection not found"));
    }

    @POST
    public RestResponse<ConnectionView> create(ConnectionInput in) {
        String name = validate(in, null);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        db.update("INSERT INTO connection (id, name, kind, role, config_json, secret_enc, max_concurrency, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, name, in.kind(), in.role(), configJson(in), secret(in), maxConcurrency(in), now, now);
        audit.record("connection.create", "connection:" + id, Map.of("name", name));
        return RestResponse.status(RestResponse.Status.CREATED, get(id));
    }

    @PUT
    @Path("/{id}")
    public ConnectionView update(String id, ConnectionInput in) {
        get(id);
        String name = validate(in, id);
        db.update("UPDATE connection SET name = ?, kind = ?, role = ?, config_json = ?, secret_enc = COALESCE(?, secret_enc),"
                + " max_concurrency = ?, updated_at = ? WHERE id = ?",
            name, in.kind(), in.role(), configJson(in), secret(in), maxConcurrency(in), Instant.now(), id);
        audit.record("connection.update", "connection:" + id, Map.of("name", name, "passwordChanged", secret(in) != null));
        return get(id);
    }

    @DELETE
    @Path("/{id}")
    public void delete(String id) {
        ConnectionView connection = get(id);
        List<String> jobs = db.query("SELECT name, config_json FROM job ORDER BY name",
                rs -> JobConfigs.connectionIds(Json.read(rs.getString(2), JobDef.class)).contains(id) ? rs.getString(1) : null)
            .stream().filter(Objects::nonNull).toList();
        if (!jobs.isEmpty()) throw ApiErrors.conflict("Still used by " + String.join(", ", jobs));
        db.update("DELETE FROM connection WHERE id = ?", id);
        audit.record("connection.delete", "connection:" + id, Map.of("name", connection.name()));
    }

    @POST
    @Path("/{id}/test")
    @Requires(Role.OPERATOR)
    public TestResult test(String id) {
        get(id);
        try (SourceConnector ignored = Connectors.openSource(configs.connection(id))) {
            return new TestResult(true, "Connected", null);
        } catch (RuntimeException e) {
            return new TestResult(false, "Could not connect", Errors.describe(e));
        }
    }

    /** Tables and views, optionally limited to one schema. */
    @GET
    @Path("/{id}/objects")
    public List<ObjectRef> objects(String id, @RestQuery String schema) {
        get(id);
        try (SourceConnector source = Connectors.openSource(configs.connection(id))) {
            return source.listObjects(schema == null || schema.isBlank() ? null : schema.trim());
        }
    }

    /** {@code name} is schema-qualified, e.g. HR.CUSTOMERS_V. */
    @GET
    @Path("/{id}/objects/{name}/schema")
    public ObjectSchema schema(String id, String name) {
        get(id);
        try (SourceConnector source = Connectors.openSource(configs.connection(id))) {
            return source.describe(ObjectRef.parse(name));
        }
    }

    /** Checks the input and returns the trimmed name. */
    private String validate(ConnectionInput in, String selfId) {
        if (in == null) throw new IllegalArgumentException("Connection details are required");
        String name = ApiErrors.required(in.name(), "Name");
        if (!Connectors.supportsSource(in.kind())) throw new IllegalArgumentException("Unsupported connection type: " + in.kind());
        if (in.role() == null) throw new IllegalArgumentException("Role is required");
        ApiErrors.required(in.url(), "JDBC URL");
        ApiErrors.range(maxConcurrency(in), 1, 32, "Max concurrency");
        boolean taken = db.one("SELECT id FROM connection WHERE name = ?", rs -> rs.getString(1), name)
            .filter(id -> !id.equals(selfId)).isPresent();
        if (taken) throw ApiErrors.conflict("A connection named " + name + " already exists");
        return name;
    }

    private static String configJson(ConnectionInput in) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", in.url().trim());
        if (in.username() != null && !in.username().isBlank()) config.put("username", in.username().trim());
        return Json.write(config);
    }

    private byte[] secret(ConnectionInput in) {
        return in.password() == null || in.password().isEmpty() ? null : secrets.encrypt(in.password());
    }

    private static int maxConcurrency(ConnectionInput in) {
        return Objects.requireNonNullElse(in.maxConcurrency(), 2);
    }

    private static ConnectionView view(ResultSet rs) throws SQLException {
        Map<String, Object> config = Json.map(rs.getString("config_json"));
        return new ConnectionView(rs.getString("id"), rs.getString("name"), rs.getString("kind"),
            ConnectionRole.valueOf(rs.getString("role")), (String) config.get("url"), (String) config.get("username"),
            rs.getInt("max_concurrency"), rs.getBoolean("has_password"), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }
}
