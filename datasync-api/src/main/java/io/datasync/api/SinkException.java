package io.datasync.api;

import java.time.Duration;

/**
 * A failed {@link Sink#apply}. A retryable failure (a timeout, HTTP 429 or 5xx) is retried with backoff,
 * waiting at least {@code retryAfter} when the target said so; a permanent one (HTTP 400) goes straight to the
 * dead letters. Other exceptions a sink throws count as retryable.
 */
public class SinkException extends RuntimeException {

    private final boolean retryable;
    private final Duration retryAfter;

    private SinkException(String message, Throwable cause, boolean retryable, Duration retryAfter) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public static SinkException retryable(String message, Duration retryAfter, Throwable cause) {
        return new SinkException(message, cause, true, retryAfter);
    }

    public static SinkException permanent(String message, Throwable cause) {
        return new SinkException(message, cause, false, null);
    }

    public boolean retryable() {
        return retryable;
    }

    /** At least how long to wait before retrying, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
