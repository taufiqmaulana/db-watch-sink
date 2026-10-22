package io.datasync.core;

public record RunResult(String jobId, String runId, RunState state, RunStats stats, String error) {}
