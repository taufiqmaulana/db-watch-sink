package io.datasync.api;

/**
 * What a sink can do and needs. {@code batchLimit} (0: no limit) caps the changes per {@link Sink#apply} call;
 * the engine splits larger batches. {@code emptyStringIsNull}: the target stores '' as NULL (Oracle), so the
 * engine sends NULL for it (design 5.5). {@code needsDeletedRows}: DELETE changes should carry the last known
 * row, which the engine then keeps in its hash index (design 5.3).
 */
public record SinkCapabilities(boolean deletes, boolean schemaEvolution, boolean ordering, int batchLimit,
                               boolean emptyStringIsNull, boolean needsDeletedRows) {}
