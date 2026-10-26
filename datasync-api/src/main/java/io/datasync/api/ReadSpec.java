package io.datasync.api;

import java.time.Duration;
import java.util.List;

/**
 * A keyset scan ordered by {@code keyColumns}, issued as short queries of {@code chunkSize} rows; each chunk
 * resumes after the last row's {@code keyColumns} values. With a {@code watermark} (null otherwise), only rows
 * whose watermark column is set, and at or after {@link WatermarkSpec#from} when given, are read; callers then
 * put the watermark column first in {@code keyColumns}.
 */
public record ReadSpec(
    ObjectRef object,
    List<String> columns,
    List<String> keyColumns,
    int fetchSize,
    int chunkSize,
    Duration queryTimeout,
    WatermarkSpec watermark) {

    public ReadSpec(ObjectRef object, List<String> columns, List<String> keyColumns, int fetchSize, int chunkSize, Duration queryTimeout) {
        this(object, columns, keyColumns, fetchSize, chunkSize, queryTimeout, null);
    }
}
