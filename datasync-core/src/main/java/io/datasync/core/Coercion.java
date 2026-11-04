package io.datasync.core;

import io.datasync.api.CanonicalType;
import io.datasync.api.CanonicalType.Kind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Simple type coercion (design 4.4): converts a value from its source type to another canonical type, e.g. an
 * Oracle NUMBER(1) or a 'Y'/'N' column to BOOLEAN. Times without a zone are taken as UTC. A value that cannot be
 * converted fails the run, naming the column.
 */
final class Coercion {

    /** Coercion targets; BYTES is neither a source nor a target. */
    static final Set<Kind> TARGETS = Set.of(Kind.BOOLEAN, Kind.INT32, Kind.INT64, Kind.DECIMAL, Kind.FLOAT64, Kind.STRING,
        Kind.DATE, Kind.TIMESTAMP, Kind.TIMESTAMP_TZ, Kind.JSON, Kind.UUID);

    private Coercion() {}

    /** The canonical type a column has after coercion to {@code kind}. */
    static CanonicalType type(Kind kind) {
        return kind == Kind.TIMESTAMP ? new CanonicalType(Kind.TIMESTAMP, 6, 0) : CanonicalType.of(kind);
    }

    static Object convert(Object value, Kind to, String column) {
        if (value == null) return null;
        try {
            return switch (to) {
                case STRING, JSON -> Watermark.format(value);
                case BOOLEAN -> bool(value);
                case INT32 -> decimal(value).intValueExact();
                case INT64 -> decimal(value).longValueExact();
                case DECIMAL -> decimal(value);
                case FLOAT64 -> value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim());
                case DATE -> dateTime(value).toLocalDate();
                case TIMESTAMP -> dateTime(value);
                case TIMESTAMP_TZ -> value instanceof OffsetDateTime t ? t : dateTime(value).atOffset(ZoneOffset.UTC);
                case UUID -> UUID.fromString(value.toString().trim());
                case BYTES -> throw new IllegalArgumentException("cannot coerce to BYTES");
            };
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot convert " + column + " value '" + value + "' to " + to + ": " + e.getMessage(), e);
        }
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return new BigDecimal(n.toString()).signum() != 0;
        return switch (value.toString().trim().toLowerCase(Locale.ROOT)) {
            case "y", "yes", "t", "true", "1" -> true;
            case "n", "no", "f", "false", "0" -> false;
            default -> throw new IllegalArgumentException("not a boolean");
        };
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        if (value instanceof BigDecimal d) return d;
        return new BigDecimal(value.toString().trim());
    }

    /** Times without a zone stay as they are; times with one are converted to UTC. */
    private static LocalDateTime dateTime(Object value) {
        return switch (value) {
            case LocalDateTime t -> t;
            case LocalDate d -> d.atStartOfDay();
            case OffsetDateTime t -> t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            case TemporalAccessor t -> LocalDateTime.from(t);
            default -> {
                String text = value.toString().trim();
                if (text.length() == 10) yield LocalDate.parse(text).atStartOfDay();
                yield text.endsWith("Z") || text.matches(".*[+-]\\d\\d:\\d\\d$")
                    ? OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
                    : LocalDateTime.parse(text.replace(' ', 'T'));
            }
        };
    }
}
