package io.datasync.server.api;

import io.datasync.server.Audit;
import io.datasync.server.Db;
import io.datasync.server.auth.CurrentUser;
import io.datasync.server.auth.Passwords;
import io.datasync.server.auth.Public;
import io.datasync.server.auth.Requires;
import io.datasync.server.auth.Role;
import io.datasync.server.auth.Sessions;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.resteasy.reactive.RestResponse;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Path("/auth")
public class AuthResource {

    public record Credentials(String username, String password) {}

    public record Me(String username, Role role, boolean mustChangePassword) {}

    public record PasswordChange(String currentPassword, String newPassword) {}

    private record StoredUser(String id, String username, String passwordHash, Role role, boolean mustChangePassword) {}

    @Inject
    Db db;

    @Inject
    Sessions sessions;

    @Inject
    CurrentUser currentUser;

    @Inject
    Audit audit;

    @POST
    @Path("/login")
    @Public
    public RestResponse<Me> login(Credentials in, @Context SecurityContext security) {
        String username = in == null || in.username() == null ? "" : in.username().trim();
        StoredUser user = db.one("SELECT id, username, password_hash, role, must_change_password FROM app_user WHERE username = ?",
            rs -> new StoredUser(rs.getString(1), rs.getString(2), rs.getString(3), Role.valueOf(rs.getString(4)), rs.getBoolean(5)),
            username).orElse(null);
        String password = in == null ? null : in.password();
        if (!Passwords.verify(Objects.requireNonNullElse(password, ""), user == null ? null : user.passwordHash())) {
            audit.record(username, "auth.login-failed", "auth", Map.of());
            throw new WebApplicationException("Invalid username or password", Response.Status.UNAUTHORIZED);
        }
        Sessions.Session session = sessions.create(user.id(), user.username(), user.role(), user.mustChangePassword());
        audit.record(user.username(), "auth.login", "user:" + user.id(), Map.of());
        return RestResponse.ResponseBuilder.ok(new Me(user.username(), user.role(), user.mustChangePassword()))
            .cookie(cookie(session.token(), security.isSecure())).build();
    }

    @POST
    @Path("/logout")
    @Requires(Role.VIEWER)
    public RestResponse<Void> logout(@CookieParam(Sessions.COOKIE) String token, @Context SecurityContext security) {
        sessions.remove(token);
        return RestResponse.ResponseBuilder.<Void>noContent().cookie(cookie(null, security.isSecure())).build();
    }

    @GET
    @Path("/me")
    public Me me() {
        Sessions.Session s = currentUser.session();
        return new Me(s.username(), s.role(), s.mustChangePassword());
    }

    @POST
    @Path("/password")
    @Requires(Role.VIEWER)
    public Me changePassword(PasswordChange in) {
        Sessions.Session s = currentUser.session();
        String hash = db.one("SELECT password_hash FROM app_user WHERE id = ?", rs -> rs.getString(1), s.userId()).orElseThrow();
        if (in == null || !Passwords.verify(Objects.requireNonNullElse(in.currentPassword(), ""), hash)) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        UserResource.checkPassword(in.newPassword());
        if (in.newPassword().equals(in.currentPassword())) throw new IllegalArgumentException("Choose a password different from the current one");
        db.update("UPDATE app_user SET password_hash = ?, must_change_password = FALSE, updated_at = ? WHERE id = ?",
            Passwords.hash(in.newPassword()), Instant.now(), s.userId());
        sessions.updateUser(s.userId(), s.role(), false);
        audit.record("user.password-changed", "user:" + s.userId(), Map.of());
        return new Me(s.username(), s.role(), false);
    }

    /** HttpOnly and SameSite=Strict; a null token clears the cookie. */
    private static NewCookie cookie(String token, boolean secure) {
        return new NewCookie.Builder(Sessions.COOKIE)
            .value(token == null ? "" : token)
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite(NewCookie.SameSite.STRICT)
            .maxAge(token == null ? 0 : -1)
            .build();
    }
}
