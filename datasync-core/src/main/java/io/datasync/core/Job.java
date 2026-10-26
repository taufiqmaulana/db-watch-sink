package io.datasync.core;

import io.datasync.api.ReadSpec;
import io.datasync.api.Sink;
import io.datasync.api.SourceConnector;

import java.util.Map;

/**
 * A job with its source and sinks open, keyed by sink id. {@code readSpec} is the full scan in key order;
 * {@code watermark} is null for SNAPSHOT_DIFF jobs.
 */
record Job(String id, SourceConnector source, ReadSpec readSpec, ColumnMapping mapping,
           Map<String, Sink> sinks, int maxDeletePercent, Watermark watermark) implements AutoCloseable {

    Job(String id, SourceConnector source, ReadSpec readSpec, ColumnMapping mapping, Map<String, Sink> sinks, int maxDeletePercent) {
        this(id, source, readSpec, mapping, sinks, maxDeletePercent, null);
    }

    @Override
    public void close() {
        sinks.values().forEach(Sink::close);
        source.close();
    }
}
