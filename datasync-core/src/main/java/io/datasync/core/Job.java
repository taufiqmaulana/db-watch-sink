package io.datasync.core;

import io.datasync.api.ReadSpec;
import io.datasync.api.Sink;
import io.datasync.api.SourceConnector;

import java.util.Map;

/**
 * A job with its source and sinks open, keyed by sink id. {@code readSpec} is the full scan in key order;
 * {@code watermark} is null for SNAPSHOT_DIFF jobs. {@code policies} says how each sink's failures are retried
 * and dead-lettered; {@code keepRows} keeps each key's last row in the index, for sinks that send deleted rows.
 */
record Job(String id, SourceConnector source, ReadSpec readSpec, ColumnMapping mapping, Map<String, Sink> sinks,
           int maxDeletePercent, Watermark watermark, Map<String, DeliveryPolicy> policies, boolean keepRows) implements AutoCloseable {

    Job(String id, SourceConnector source, ReadSpec readSpec, ColumnMapping mapping, Map<String, Sink> sinks, int maxDeletePercent) {
        this(id, source, readSpec, mapping, sinks, maxDeletePercent, null);
    }

    Job(String id, SourceConnector source, ReadSpec readSpec, ColumnMapping mapping, Map<String, Sink> sinks, int maxDeletePercent,
        Watermark watermark) {
        this(id, source, readSpec, mapping, sinks, maxDeletePercent, watermark, Map.of(), false);
    }

    DeliveryPolicy policy(String sinkId) {
        return policies.getOrDefault(sinkId, DeliveryPolicy.ONCE);
    }

    @Override
    public void close() {
        sinks.values().forEach(Sink::close);
        source.close();
    }
}
