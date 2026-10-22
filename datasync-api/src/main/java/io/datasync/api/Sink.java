package io.datasync.api;

public interface Sink extends AutoCloseable {
    String type();
    /** Create or verify the target. */
    void init(SinkContext ctx, ObjectSchema schema);
    /** Apply a batch. MUST be idempotent: re-applying the same batch is harmless. */
    ApplyResult apply(ChangeBatch batch);
    SinkCapabilities capabilities();
    HealthStatus health();

    @Override
    void close();
}
