package io.datasync.core;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fixed canonical serialization behind key and row hashes (design 4.1): UTF-8 JSON, decimals without
 * trailing zeros, timestamps as UTC ISO-8601 with microseconds, and {@code null} distinct from {@code ""}.
 */
final class Canonical {

    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Canonical() {}

    static Object value(Object v) {
        return switch (v) {
            case null -> null;
            case BigDecimal d -> d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
            case LocalDateTime t -> MICROS.format(t);
            case OffsetDateTime t -> MICROS.format(t.withOffsetSameInstant(ZoneOffset.UTC)) + "Z";
            case LocalDate d -> d.toString();
            case byte[] b -> Base64.getEncoder().encodeToString(b);
            case UUID u -> u.toString();
            default -> v;
        };
    }

    /** JSON object of the given columns, in the given order. */
    static String json(Map<String, Object> row, List<String> columns) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        columns.forEach(c -> canonical.put(c, value(row.get(c))));
        return write(canonical);
    }

    /** JSON array of the given columns' values, in the given order. */
    static String values(Map<String, Object> row, List<String> columns) {
        return write(columns.stream().map(c -> value(row.get(c))).toList());
    }

    static Map<String, Object> parse(String json) {
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 truncated to 16 bytes, hex encoded. */
    static String hash(String text) {
        return HexFormat.of().formatHex(Arrays.copyOf(sha256(text), 16));
    }

    static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
