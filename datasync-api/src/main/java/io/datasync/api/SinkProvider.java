package io.datasync.api;

/** ServiceLoader entry point for sinks. {@code connection} is null for sinks that need none (e.g. webhook). */
public interface SinkProvider {
    boolean supports(String sinkType);
    Sink open(ConnectionConfig connection);
}
