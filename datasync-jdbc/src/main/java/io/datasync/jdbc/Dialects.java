package io.datasync.jdbc;

import java.util.Optional;
import java.util.ServiceLoader;

final class Dialects {

    private Dialects() {}

    /** Finds the dialect for a connection kind such as "jdbc-postgres". */
    static Optional<SqlDialect> forKind(String kind) {
        if (kind == null || !kind.startsWith("jdbc-")) return Optional.empty();
        String vendor = kind.substring("jdbc-".length());
        return ServiceLoader.load(SqlDialect.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(d -> d.vendor().equals(vendor))
            .findFirst();
    }
}
