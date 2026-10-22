package io.datasync.core;

import io.datasync.api.ConnectionConfig;
import io.datasync.api.Sink;
import io.datasync.api.SinkProvider;
import io.datasync.api.SourceConnector;
import io.datasync.api.SourceProvider;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Predicate;

/** Finds installed connectors through {@link ServiceLoader}. */
public final class Connectors {

    private Connectors() {}

    public static boolean supportsSource(String connectionKind) {
        return find(SourceProvider.class, p -> p.supports(connectionKind)).isPresent();
    }

    public static SourceConnector openSource(ConnectionConfig connection) {
        return find(SourceProvider.class, p -> p.supports(connection.kind()))
            .orElseThrow(() -> new IllegalArgumentException("no connector installed for connection kind " + connection.kind()))
            .open(connection);
    }

    /** {@code connection} is null for sink types that need none. */
    public static Sink openSink(String type, ConnectionConfig connection) {
        return find(SinkProvider.class, p -> p.supports(type))
            .orElseThrow(() -> new IllegalArgumentException("no connector installed for sink type " + type))
            .open(connection);
    }

    private static <P> Optional<P> find(Class<P> type, Predicate<P> supports) {
        return ServiceLoader.load(type).stream().map(ServiceLoader.Provider::get).filter(supports).findFirst();
    }
}
