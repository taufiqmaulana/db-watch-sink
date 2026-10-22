package io.datasync.core;

import io.datasync.api.CanonicalType;

import java.util.List;
import java.util.Map;

/** A dry run of a job: how source columns map to the target, and sample rows as the sinks would receive them. */
public record Preview(List<ColumnPreview> columns, List<Map<String, Object>> rows) {

    public record ColumnPreview(String source, String target, CanonicalType type, boolean key, boolean nullable) {}
}
