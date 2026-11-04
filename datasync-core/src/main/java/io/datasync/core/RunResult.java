package io.datasync.core;

import java.util.List;

/** {@code laggingSinks}: sinks this run flagged RESNAPSHOT_REQUIRED for falling too far behind (design 6). */
public record RunResult(String jobId, String runId, RunState state, RunStats stats, String error, List<String> laggingSinks) {}
