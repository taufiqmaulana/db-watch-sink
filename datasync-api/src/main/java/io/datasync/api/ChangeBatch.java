package io.datasync.api;

import java.util.List;

public record ChangeBatch(String jobId, String sinkId, long batchId, ObjectSchema schema, List<Change> changes) {}
