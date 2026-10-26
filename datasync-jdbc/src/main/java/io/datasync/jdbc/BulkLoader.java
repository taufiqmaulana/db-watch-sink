package io.datasync.jdbc;

import io.datasync.api.ObjectRef;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface BulkLoader {

    /**
     * Called once when the sink starts, outside any batch transaction: creates whatever staging needs
     * that cannot be created mid-transaction (Oracle DDL commits implicitly).
     */
    default void prepare(Connection c, ObjectRef target, List<String> columns) throws SQLException {}

    /**
     * Loads {@code rows} into a transaction-scoped staging table with the given columns of {@code target}
     * and returns its quoted name.
     */
    String stage(Connection c, ObjectRef target, List<String> columns, List<List<Object>> rows) throws SQLException;
}
