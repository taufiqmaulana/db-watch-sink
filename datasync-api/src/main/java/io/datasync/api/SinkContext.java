package io.datasync.api;

import java.util.Map;

/** {@code config} is the sink's own section of the job config (target, deleteMode, ...). */
public record SinkContext(String jobId, String sinkId, Map<String, Object> config) {}
