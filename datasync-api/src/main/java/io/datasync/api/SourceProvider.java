package io.datasync.api;

/** ServiceLoader entry point for sources. */
public interface SourceProvider {
    boolean supports(String connectionKind);
    SourceConnector open(ConnectionConfig connection);
}
