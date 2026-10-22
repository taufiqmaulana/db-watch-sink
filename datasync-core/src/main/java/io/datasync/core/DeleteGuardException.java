package io.datasync.core;

/** The run would delete more than the job allows, which usually means a failed or partial source read. */
final class DeleteGuardException extends RuntimeException {

    DeleteGuardException(String message) {
        super(message);
    }
}
