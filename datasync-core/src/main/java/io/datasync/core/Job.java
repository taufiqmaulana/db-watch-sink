package io.datasync.core;

import io.datasync.api.ReadSpec;
import io.datasync.api.Sink;
import io.datasync.api.SourceConnector;

import java.util.Map;

/** A job with its source and sinks open, keyed by sink id. */
record Job(String id, SourceConnector source, ReadSpec readSpec, ColumnMapping mapping,
           Map<String, Sink> sinks, int maxDeletePercent) implements AutoCloseable {

    @Override
    public void close() {
        sinks.values().forEach(Sink::close);
        source.close();
    }
}
