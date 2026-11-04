package io.datasync.core;

import io.datasync.core.Outbox.SinkStatus;

import java.time.Instant;

/**
 * What one delivery pass of a sink did: batches and changes delivered, failed attempts (retried or not), batches moved
 * to the dead letters, and where the sink stands now: its status (null once the job no longer has it), its last error,
 * when it retries next, and how many batches are still waiting.
 */
public record DeliveryResult(String jobId, String sinkId, int batches, long changes, long failedAttempts, long deadLetters,
                             SinkStatus status, String error, Instant retryAt, long backlog) {

    /** Delivered everything it had, or nothing was due. */
    public boolean caughtUp() {
        return status == SinkStatus.ACTIVE && backlog == 0;
    }
}
