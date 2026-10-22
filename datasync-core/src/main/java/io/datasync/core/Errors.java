package io.datasync.core;

import java.util.stream.Stream;

public final class Errors {

    private Errors() {}

    /** The message chain, e.g. "apply of batch 3 failed: ERROR: permission denied", skipping repeated text. */
    public static String describe(Throwable e) {
        StringBuilder out = new StringBuilder();
        Stream.iterate(e, t -> t != null, Throwable::getCause)
            .map(t -> t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())
            .filter(m -> out.indexOf(m) < 0)
            .forEach(m -> out.append(out.isEmpty() ? "" : ": ").append(m));
        return out.toString();
    }
}
