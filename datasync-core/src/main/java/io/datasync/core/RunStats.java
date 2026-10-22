package io.datasync.core;

public record RunStats(long rowsRead, long upserts, long deletes, long unchanged) {}
