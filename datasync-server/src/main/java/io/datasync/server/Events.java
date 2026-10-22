package io.datasync.server;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.inject.Singleton;

import java.time.Duration;

/** Live updates for the UI, sent over SSE. A ping every 25 s keeps idle proxies from closing the stream. */
@Singleton
public class Events {

    /** {@code type} is run-started, run-finished, job-changed or ping. */
    public record Event(String type, String jobId, String runId, String state) {}

    private final BroadcastProcessor<Event> processor = BroadcastProcessor.create();

    public synchronized void publish(Event event) {
        processor.onNext(event);
    }

    public Multi<Event> stream() {
        return Multi.createBy().merging().streams(processor,
            Multi.createFrom().ticks().every(Duration.ofSeconds(25)).map(t -> new Event("ping", null, null, null)));
    }
}
