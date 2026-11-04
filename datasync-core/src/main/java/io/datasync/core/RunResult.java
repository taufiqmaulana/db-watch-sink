package io.datasync.core;

/** {@code pauseJob}: a sink with onDeadLetter: PAUSE dead-lettered a batch, so the job should be paused until someone looks. */
public record RunResult(String jobId, String runId, RunState state, RunStats stats, String error, boolean pauseJob) {}
