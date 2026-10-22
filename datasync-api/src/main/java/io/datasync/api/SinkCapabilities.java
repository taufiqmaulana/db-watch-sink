package io.datasync.api;

public record SinkCapabilities(boolean deletes, boolean schemaEvolution, boolean ordering, int batchLimit) {}
