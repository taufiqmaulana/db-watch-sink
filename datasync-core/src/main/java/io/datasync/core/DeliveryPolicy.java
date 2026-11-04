package io.datasync.core;

import io.datasync.core.config.Durations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How a sink's failed batches are handled (design 6): retried up to {@code maxAttempts} times with exponential
 * backoff and jitter, then moved to the dead letters. With {@code onDeadLetter: PAUSE} the run stops and the
 * job is paused; with {@code SKIP} the run carries on without that batch for that sink.
 */
record DeliveryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, OnDeadLetter onDeadLetter) {

    enum OnDeadLetter { PAUSE, SKIP }

    /** Policy for one attempt and no retries, used where nothing is configured (tests). */

    /** From a sink's config: {@code retry: { maxAttempts: 5, initialBackoff: 1s, maxBackoff: 1m }, onDeadLetter: PAUSE}. */
    static DeliveryPolicy of(Map<String, Object> sink) {
        @SuppressWarnings("unchecked")
        Map<String, Object> retry = sink.get("retry") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        int maxAttempts = Integer.parseInt(String.valueOf(retry.getOrDefault("maxAttempts", 5)));
        if (maxAttempts < 1 || maxAttempts > 20) throw new IllegalArgumentException("retry.maxAttempts must be between 1 and 20");
        return new DeliveryPolicy(maxAttempts,
            Durations.parse(String.valueOf(retry.getOrDefault("initialBackoff", "1s"))),
            Durations.parse(String.valueOf(retry.getOrDefault("maxBackoff", "1m"))),
            OnDeadLetter.valueOf(String.valueOf(sink.getOrDefault("onDeadLetter", "PAUSE")).toUpperCase()));
    }

    /**
     * The wait before attempt {@code attempt + 1}: the initial backoff doubled per attempt, capped, with up to
     * 20% jitter; never less than {@code retryAfter} (capped at 5 minutes) when the target asked for it.
     */
    Duration backoff(int attempt, Duration retryAfter) {
        long ms = Math.min(initialBackoff.toMillis() << Math.min(attempt - 1, 20), maxBackoff.toMillis());
        ms += ms > 0 ? ThreadLocalRandom.current().nextLong(ms / 5 + 1) : 0;
        if (retryAfter != null) ms = Math.max(ms, Math.min(retryAfter.toMillis(), Duration.ofMinutes(5).toMillis()));
        return Duration.ofMillis(ms);
    }
}
