package io.datasync.sink.webhook;

import io.datasync.api.ConnectionConfig;
import io.datasync.api.Sink;
import io.datasync.api.SinkProvider;

/** ServiceLoader entry point for the "webhook" sink type, and for connections of kind "webhook". */
public final class WebhookProvider implements SinkProvider {

    @Override
    public boolean supports(String sinkType) {
        return "webhook".equals(sinkType);
    }

    /** {@code connection} holds the URL and credentials; without one, they come from the sink's own config. */
    @Override
    public Sink open(ConnectionConfig connection) {
        return new WebhookSink(connection);
    }
}
