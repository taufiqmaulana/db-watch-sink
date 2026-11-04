package io.datasync.server.auth;

import jakarta.inject.Singleton;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Slows down password guessing: after 5 failed sign-ins for a username, or 20 from one address, within 15 minutes,
 * further attempts are refused for 15 minutes. A successful sign-in clears the username's count. The address limit is
 * higher because many users can share one address behind a proxy or NAT.
 */
@Singleton
public class LoginThrottle {

    static final int MAX_PER_USER = 5;
    static final int MAX_PER_ADDRESS = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    private static final int PRUNE_ABOVE = 10_000;

    private record Count(int failures, Instant since, Instant lockedUntil) {}

    private final Map<String, Count> counts = new ConcurrentHashMap<>();

    /** How long sign-in stays refused for this username or address, if either is locked. */
    public Optional<Duration> lockedFor(String username, String address) {
        Instant now = Instant.now();
        return Stream.of(userKey(username), addressKey(address))
            .map(counts::get)
            .filter(c -> c != null && c.lockedUntil() != null && c.lockedUntil().isAfter(now))
            .map(c -> Duration.between(now, c.lockedUntil()))
            .max(Duration::compareTo);
    }

    /** Records a failed sign-in; true when it just locked the username or the address. */
    public boolean failed(String username, String address) {
        if (counts.size() > PRUNE_ABOVE) prune();
        boolean userLocked = count(userKey(username), MAX_PER_USER);
        boolean addressLocked = count(addressKey(address), MAX_PER_ADDRESS);
        return userLocked || addressLocked;
    }

    public void succeeded(String username) {
        counts.remove(userKey(username));
    }

    private boolean count(String key, int max) {
        Instant now = Instant.now();
        Count c = counts.compute(key, (k, old) -> {
            Count fresh = old == null || old.since().plus(WINDOW).isBefore(now) ? new Count(0, now, null) : old;
            int failures = fresh.failures() + 1;
            return new Count(failures, fresh.since(), failures >= max ? now.plus(LOCK) : fresh.lockedUntil());
        });
        return c.failures() == max;
    }

    private void prune() {
        Instant now = Instant.now();
        counts.values().removeIf(c -> c.since().plus(WINDOW).isBefore(now) && (c.lockedUntil() == null || c.lockedUntil().isBefore(now)));
    }

    private static String userKey(String username) {
        return "user:" + username.toLowerCase(Locale.ROOT);
    }

    private static String addressKey(String address) {
        return "address:" + address;
    }
}
