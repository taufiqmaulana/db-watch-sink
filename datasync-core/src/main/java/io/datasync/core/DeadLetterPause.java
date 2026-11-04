package io.datasync.core;

/** A sink's batch went to the dead letters and the sink's policy is {@code onDeadLetter: PAUSE}: stop the run and pause the job. */
final class DeadLetterPause extends RuntimeException {

    DeadLetterPause(String message, Throwable cause) {
        super(message, cause);
    }
}
