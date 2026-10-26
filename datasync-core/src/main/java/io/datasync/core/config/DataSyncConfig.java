package io.datasync.core.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The YAML configuration (design section 13). Connections and sinks stay loosely typed maps because
 * their fields depend on the connector.
 */
public record DataSyncConfig(List<Map<String, Object>> connections, List<JobDef> jobs) {

    public DataSyncConfig {
        connections = Objects.requireNonNullElse(connections, List.of());
        jobs = Objects.requireNonNullElse(jobs, List.of());
    }

    public enum Mode { WATERMARK, SNAPSHOT_DIFF, HYBRID }

    public record JobDef(
        String name,
        SourceDef source,
        List<String> keys,
        ColumnsDef columns,
        Mode mode,
        Integer batchSize,
        Integer fetchSize,
        Integer maxDeletePercent,
        Integer queryTimeoutSeconds,
        List<Map<String, Object>> sinks,
        String schedule,
        String every,
        Boolean fixedDelay,
        Integer jitterPercent) {

        /**
         * Scheduling (design 5A): {@code schedule} is a 5-field cron expression; {@code every} is an interval such
         * as {@code 30s}, {@code 5m} or {@code 1h} and takes precedence when set. {@code fixedDelay} measures an
         * interval from the end of a run instead of its start. The CLI ignores these and runs once.
         */
        public JobDef {
            schedule = schedule == null || schedule.isBlank() ? "*/5 * * * *" : schedule.trim();
            every = every == null || every.isBlank() ? null : every.trim();
            fixedDelay = Objects.requireNonNullElse(fixedDelay, false);
            jitterPercent = Objects.requireNonNullElse(jitterPercent, 10);
            keys = Objects.requireNonNullElse(keys, List.of());
            columns = Objects.requireNonNullElse(columns, new ColumnsDef(null, null, null));
            mode = Objects.requireNonNullElse(mode, Mode.SNAPSHOT_DIFF);
            batchSize = Objects.requireNonNullElse(batchSize, 10_000);
            fetchSize = Objects.requireNonNullElse(fetchSize, 5_000);
            maxDeletePercent = Objects.requireNonNullElse(maxDeletePercent, 20);
            queryTimeoutSeconds = Objects.requireNonNullElse(queryTimeoutSeconds, 300);
            sinks = Objects.requireNonNullElse(sinks, List.of());
        }

        public JobDef withName(String newName) {
            return new JobDef(newName, source, keys, columns, mode, batchSize, fetchSize, maxDeletePercent, queryTimeoutSeconds, sinks,
                schedule, every, fixedDelay, jitterPercent);
        }
    }

    public record SourceDef(String connection, String object) {}

    /** {@code include} is {@code "*"} (default) or a list of source column names. */
    public record ColumnsDef(List<String> include, List<String> exclude, Map<String, String> rename) {

        public ColumnsDef {
            include = Objects.requireNonNullElse(include, List.of("*"));
            exclude = Objects.requireNonNullElse(exclude, List.of());
            rename = Objects.requireNonNullElse(rename, Map.of());
        }
    }
}
