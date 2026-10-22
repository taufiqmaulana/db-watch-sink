package io.datasync.api;

import java.time.Duration;
import java.util.List;

/** A full keyset scan ordered by {@code keyColumns}, issued as short queries of {@code chunkSize} rows. */
public record ReadSpec(
    ObjectRef object,
    List<String> columns,
    List<String> keyColumns,
    int fetchSize,
    int chunkSize,
    Duration queryTimeout) {}
