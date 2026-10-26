package io.datasync.core.config;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations written the way the config writes them: 30s, 5m, 2h. */
public final class Durations {

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([smh])");

    private Durations() {}

    public static Duration parse(String text) {
        Matcher m = DURATION.matcher(text.trim().toLowerCase());
        if (!m.matches()) throw new IllegalArgumentException("'" + text + "' must look like 30s, 5m or 2h");
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
