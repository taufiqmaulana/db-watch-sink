package io.datasync.api;

import java.util.List;

public record ObjectSchema(ObjectRef ref, List<Column> columns, List<String> keyColumns) {}
