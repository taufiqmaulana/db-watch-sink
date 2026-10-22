package io.datasync.api;

import java.util.List;
import java.util.Set;

public interface SourceConnector extends AutoCloseable {
    String type();
    ObjectSchema describe(ObjectRef ref);
    List<ObjectRef> listObjects(String schemaFilter);
    RecordCursor read(ReadSpec spec);
    Set<String> primaryKeyOf(ObjectRef ref);

    @Override
    void close();
}
