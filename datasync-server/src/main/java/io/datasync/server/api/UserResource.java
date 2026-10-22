package io.datasync.server.api;

import io.datasync.server.Audit;
import io.datasync.server.Db;
import io.datasync.server.auth.CurrentUser;
import io.datasync.server.auth.Passwords;
import io.datasync.server.auth.Requires;
import io.datasync.server.auth.Role;
import io.datasync.server.auth.Sessions;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestResponse;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Local users. A password set by an admin must be changed at the user's next sign-in. */
@Path("/users")
public class UserResource {

    public record UserView(String id, String username, Role role, boolean mustChangePassword, Instant createdAt, Instant updatedAt) {}

    public record NewUser(String username, String password, Role role) {}

    /** Both optional; a new password is a reset that the user must change at next sign-in. */
    public record UserUpdate(Role role, String password) {}

    private static final String SELECT = "SELECT id, username, role, must_change_password, created_at, updated_at FROM app_user";

    @Inject
    Db db;

    @Inject
    Sessions sessions;

    @Inject
    CurrentUser currentUser;

    @Inject
    Audit audit;

    @ConfigProperty(name = "datasync.admin-password")
    Optional<String> adminPassword;

    /** Creates the bootstrap admin on first start, with DATASYNC_ADMIN_PASSWORD or a generated password. */
    void bootstrap(@Observes StartupEvent ignored) {
        if (db.one("SELECT COUNT(*) FROM app_user", rs -> rs.getLong(1)).orElse(0L) > 0) return;
        String password = adminPassword.orElseGet(() -> {
            byte[] bytes = new byte[12];
            new SecureRandom().nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        });
        insert("admin", password, Role.ADMIN);
        if (adminPassword.isEmpty()) Log.warnf("Created user 'admin' with password %s. It must be changed at first sign-in.", password);
        else Log.info("Created user 'admin' with the configured admin password. It must be changed at first sign-in.");
    }

    @GET
    @Requires(Role.ADMIN)
    public List<UserView> list() {
        return db.query(SELECT + " ORDER BY username", UserResource::view);
    }

    @POST
    public RestResponse<UserView> create(NewUser in) {
        if (in == null) throw new IllegalArgumentException("User details are required");
        String username = ApiErrors.required(in.username(), "Username");
        if (!username.matches("[A-Za-z0-9._@-]{1,64}")) {
            throw new IllegalArgumentException("Username may only contain letters, digits and . _ @ -");
        }
        if (in.role() == null) throw new IllegalArgumentException("Role is required");
        checkPassword(in.password());
        if (db.one("SELECT 1 FROM app_user WHERE username = ?", rs -> 1, username).isPresent()) {
            throw ApiErrors.conflict("A user named " + username + " already exists");
        }
        String id = insert(username, in.password(), in.role());
        audit.record("user.create", "user:" + id, Map.of("username", username, "role", in.role()));
        return RestResponse.status(RestResponse.Status.CREATED, get(id));
    }

    @PUT
    @Path("/{id}")
    public UserView update(String id, UserUpdate in) {
        UserView user = get(id);
        Role role = in.role() == null ? user.role() : in.role();
        if (user.role() == Role.ADMIN && role != Role.ADMIN && adminCount() == 1) throw ApiErrors.conflict("Keep at least one admin");
        boolean reset = in.password() != null && !in.password().isEmpty();
        if (reset) checkPassword(in.password());
        db.tx(c -> {
            Db.update(c, "UPDATE app_user SET role = ?, updated_at = ? WHERE id = ?", role, Instant.now(), id);
            if (reset) Db.update(c, "UPDATE app_user SET password_hash = ?, must_change_password = TRUE WHERE id = ?", Passwords.hash(in.password()), id);
            return null;
        });
        sessions.updateUser(id, role, reset || user.mustChangePassword());
        audit.record("user.update", "user:" + id, Map.of("username", user.username(), "role", role, "passwordReset", reset));
        return get(id);
    }

    @DELETE
    @Path("/{id}")
    public void delete(String id) {
        UserView user = get(id);
        if (id.equals(currentUser.session().userId())) throw ApiErrors.conflict("You cannot delete your own account");
        if (user.role() == Role.ADMIN && adminCount() == 1) throw ApiErrors.conflict("Keep at least one admin");
        db.update("DELETE FROM app_user WHERE id = ?", id);
        sessions.revokeUser(id);
        audit.record("user.delete", "user:" + id, Map.of("username", user.username()));
    }

    static void checkPassword(String password) {
        if (password == null || password.length() < Passwords.MIN_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + Passwords.MIN_LENGTH + " characters");
        }
    }

    private UserView get(String id) {
        return db.one(SELECT + " WHERE id = ?", UserResource::view, id).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private long adminCount() {
        return db.one("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN'", rs -> rs.getLong(1)).orElse(0L);
    }

    private String insert(String username, String password, Role role) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        db.update("INSERT INTO app_user (id, username, password_hash, role, must_change_password, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, TRUE, ?, ?)", id, username, Passwords.hash(password), role, now, now);
        return id;
    }

    private static UserView view(ResultSet rs) throws SQLException {
        return new UserView(rs.getString("id"), rs.getString("username"), Role.valueOf(rs.getString("role")),
            rs.getBoolean("must_change_password"), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }
}
