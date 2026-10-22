package io.datasync.api;

public record ApplyResult(long upserts, long deletes) {}
