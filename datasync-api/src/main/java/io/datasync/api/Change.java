package io.datasync.api;

import java.time.Instant;
import java.util.Map;

/**
 * One row change. {@code key} and {@code row} are keyed by target (renamed) column name.
 * {@code row} is null for DELETE.
 */
public record Change(
    Op op,
    Map<String, Object> key,
    Map<String, Object> row,
    String rowHash,
    String idempotencyKey,
    Instant observedAt,
    long seq) {

    public enum Op { UPSERT, DELETE }
}
