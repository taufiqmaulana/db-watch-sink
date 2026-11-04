package io.datasync.core;

import java.util.Map;

/**
 * {@code pauseJob}: a sink with onDeadLetter: PAUSE dead-lettered a batch, so the job should be paused until someone looks.
 * {@code sinks} has an entry for every sink that failed at least once during the run, by sink id.
 */
public record RunResult(String jobId, String runId, RunState state, RunStats stats, String error, boolean pauseJob,
                        Map<String, SinkFailures> sinks) {

    /** Failed delivery attempts (retried or not) and batches moved to the dead letters. */
    public record SinkFailures(long attempts, long deadLetters) {}

    public long deadLetters() {
        return sinks.values().stream().mapToLong(SinkFailures::deadLetters).sum();
    }
}
