package io.datasync.api;

import java.util.Iterator;
import java.util.Map;

/** Rows keyed by source column name, values in the canonical Java type of each column. */
public interface RecordCursor extends Iterator<Map<String, Object>>, AutoCloseable {

    @Override
    void close();
}
