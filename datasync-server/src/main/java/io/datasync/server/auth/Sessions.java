package io.datasync.server.auth;

import jakarta.inject.Singleton;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory sessions with an idle timeout. Single node, so a restart signs everyone out. */
@Singleton
public class Sessions {

    public static final String COOKIE = "datasync_session";
    private static final Duration IDLE_TIMEOUT = Duration.ofHours(8);

    public record Session(String token, String userId, String username, Role role, boolean mustChangePassword, Instant expiresAt) {}

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Session create(String userId, String username, Role role, boolean mustChangePassword) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session s = new Session(token, userId, username, role, mustChangePassword, Instant.now().plus(IDLE_TIMEOUT));
        sessions.put(token, s);
        return s;
    }

    /** The live session for a token, with its idle timeout extended. */
    public Optional<Session> find(String token) {
        if (token == null) return Optional.empty();
        Session s = sessions.computeIfPresent(token, (t, old) -> old.expiresAt().isBefore(Instant.now()) ? null
            : new Session(t, old.userId(), old.username(), old.role(), old.mustChangePassword(), Instant.now().plus(IDLE_TIMEOUT)));
        return Optional.ofNullable(s);
    }

    public void remove(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Applies a role or password-state change to the user's open sessions. */
    public void updateUser(String userId, Role role, boolean mustChangePassword) {
        sessions.replaceAll((t, s) -> s.userId().equals(userId)
            ? new Session(t, userId, s.username(), role, mustChangePassword, s.expiresAt()) : s);
    }

    public void revokeUser(String userId) {
        sessions.values().removeIf(s -> s.userId().equals(userId));
    }
}
