package io.datasync.jdbc;

import io.datasync.api.ObjectRef;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface BulkLoader {
    /**
     * Creates a transaction-scoped staging table with the given columns of {@code target},
     * loads {@code rows} into it and returns its quoted name.
     */
    String stage(Connection c, ObjectRef target, List<String> columns, List<List<Object>> rows) throws SQLException;
}
