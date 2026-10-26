package io.datasync.core;

import io.datasync.api.CanonicalType;
import io.datasync.api.CanonicalType.Kind;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * The watermark column of a WATERMARK or HYBRID job (design 5.1). The cursor is the canonical text of the
 * highest value delivered; the next read starts at the cursor minus the overlap window, which applies to
 * time columns only (an id column re-reads just its last value, which the hash index then drops).
 */
record Watermark(String column, CanonicalType type, Duration overlap) {

    static final Set<Kind> KINDS = EnumSet.of(Kind.DATE, Kind.TIMESTAMP, Kind.TIMESTAMP_TZ, Kind.INT32, Kind.INT64, Kind.DECIMAL);

    /** The lower bound of the next read, in the column's canonical Java type. */
    Object from(String cursor) {
        return switch (type.kind()) {
            case TIMESTAMP -> LocalDateTime.parse(cursor).minus(overlap);
            case TIMESTAMP_TZ -> OffsetDateTime.parse(cursor).minus(overlap);
            case DATE -> LocalDate.parse(cursor).minusDays((overlap.toSeconds() + 86_399) / 86_400);
            case INT32, INT64 -> Long.parseLong(cursor);
            default -> new BigDecimal(cursor);
        };
    }

    static String format(Object value) {
        Object canonical = Canonical.value(value);
        return canonical instanceof BigDecimal d ? d.toPlainString() : canonical.toString();
    }

    /** The larger of two values of this column; either may be null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object max(Object a, Object b) {
        if (a == null) return b;
        if (b == null) return a;
        return ((Comparable) a).compareTo(b) >= 0 ? a : b;
    }
}
