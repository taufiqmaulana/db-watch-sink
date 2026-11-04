package io.datasync.api;

/** {@code emptyStringIsNull}: the target stores '' as NULL (Oracle), so the engine sends NULL for it (design 5.5). */
public record SinkCapabilities(boolean deletes, boolean schemaEvolution, boolean ordering, int batchLimit, boolean emptyStringIsNull) {}
