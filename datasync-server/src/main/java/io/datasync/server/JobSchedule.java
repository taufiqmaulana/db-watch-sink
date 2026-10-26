package io.datasync.server;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.datasync.core.config.DataSyncConfig.JobDef;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a job runs (design 5A): a 5-field cron expression in the server's time zone, or a fixed interval of at
 * least 10 seconds measured from each run's start (or its end with {@code fixedDelay}).
 */
public record JobSchedule(ExecutionTime cron, Duration every, boolean fixedDelay, int jitterPercent) {

    public static final Duration MIN_INTERVAL = Duration.ofSeconds(10);

    private static final CronParser CRON = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    private static final Pattern INTERVAL = Pattern.compile("(\\d+)\\s*([smh])");

    /** The schedule of {@code def}, or a 400-style {@link IllegalArgumentException} naming what is wrong. */
    public static JobSchedule of(JobDef def) {
        int jitter = def.jitterPercent();
        if (jitter < 0 || jitter > 50) throw new IllegalArgumentException("Jitter must be between 0 and 50 percent");
        if (def.every() != null) {
            Duration every = interval(def.every());
            if (every.compareTo(MIN_INTERVAL) < 0) throw new IllegalArgumentException("Run at most every 10 seconds");
            return new JobSchedule(null, every, def.fixedDelay(), jitter);
        }
        try {
            Cron cron = CRON.parse(def.schedule()).validate();
            return new JobSchedule(ExecutionTime.forCron(cron), null, false, jitter);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression '" + def.schedule() + "': " + e.getMessage());
        }
    }

    /** The first start strictly after {@code after}, before jitter. */
    public Instant next(Instant after) {
        if (every != null) return after.plus(every);
        return cron.nextExecution(after.atZone(ZoneId.systemDefault()))
            .map(t -> t.toInstant())
            .orElseThrow(() -> new IllegalStateException("cron expression has no future run"));
    }

    /** A random delay of up to {@code jitterPercent} of the time between runs, to spread jobs that share a source. */
    public Duration jitter(Instant planned) {
        long periodMs = Duration.between(planned, next(planned)).toMillis();
        long maxMs = periodMs * jitterPercent / 100;
        return maxMs <= 0 ? Duration.ZERO : Duration.ofMillis(ThreadLocalRandom.current().nextLong(maxMs));
    }

    /** "30s", "5m" or "2h". */
    static Duration interval(String text) {
        Matcher m = INTERVAL.matcher(text.trim().toLowerCase());
        if (!m.matches()) throw new IllegalArgumentException("Interval '" + text + "' must look like 30s, 5m or 2h");
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
