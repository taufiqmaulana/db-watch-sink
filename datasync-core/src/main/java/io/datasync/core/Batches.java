package io.datasync.core;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datasync.api.CanonicalType;
import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.Column;
import io.datasync.api.ObjectSchema;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A batch as stored in the dead letters: JSON with canonical values and the target schema, so that replaying it
 * restores each value to its column's type (database sinks bind typed values).
 */
final class Batches {

    private record StoredChange(Change.Op op, Map<String, Object> key, Map<String, Object> row, String rowHash,
                                String idempotencyKey, String observedAt, long seq) {}

    private record StoredBatch(String jobId, String sinkId, long batchId, ObjectSchema schema, List<StoredChange> changes) {}

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Batches() {}

    static String encode(ChangeBatch batch) {
        List<StoredChange> changes = batch.changes().stream()
            .map(c -> new StoredChange(c.op(), canonical(c.key()), canonical(c.row()), c.rowHash(), c.idempotencyKey(),
                c.observedAt() == null ? null : c.observedAt().toString(), c.seq()))
            .toList();
        try {
            return JSON.writeValueAsString(new StoredBatch(batch.jobId(), batch.sinkId(), batch.batchId(), batch.schema(), changes));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static ChangeBatch decode(String json) {
        StoredBatch stored;
        try {
            stored = JSON.readValue(json, StoredBatch.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable dead letter", e);
        }
        Map<String, CanonicalType> types = stored.schema().columns().stream().collect(Collectors.toMap(Column::name, Column::type));
        List<Change> changes = stored.changes().stream()
            .map(c -> new Change(c.op(), typed(c.key(), types), typed(c.row(), types), c.rowHash(), c.idempotencyKey(),
                c.observedAt() == null ? null : Instant.parse(c.observedAt()), c.seq()))
            .toList();
        return new ChangeBatch(stored.jobId(), stored.sinkId(), stored.batchId(), stored.schema(), changes);
    }

    private static Map<String, Object> canonical(Map<String, Object> values) {
        return transform(values, (name, v) -> Canonical.value(v));
    }

    private static Map<String, Object> typed(Map<String, Object> values, Map<String, CanonicalType> types) {
        return transform(values, (name, v) -> {
            if (v == null || !types.containsKey(name)) return v;
            return switch (types.get(name).kind()) {
                case BYTES -> Base64.getDecoder().decode(v.toString());
                case STRING, JSON -> v.toString();
                default -> Coercion.convert(v, types.get(name).kind(), name);
            };
        });
    }

    private interface ValueMapper {
        Object map(String name, Object value);
    }

    private static Map<String, Object> transform(Map<String, Object> values, ValueMapper mapper) {
        if (values == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k, mapper.map(k, v)));
        return out;
    }
}
