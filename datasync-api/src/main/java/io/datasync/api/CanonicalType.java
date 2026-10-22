package io.datasync.api;

/**
 * Vendor-neutral column type. {@code precision}/{@code scale} apply to DECIMAL
 * (precision 0 means unbounded) and precision to TIMESTAMP (fractional digits).
 */
public record CanonicalType(Kind kind, int precision, int scale) {

    public enum Kind { BOOLEAN, INT32, INT64, DECIMAL, FLOAT64, STRING, DATE, TIMESTAMP, TIMESTAMP_TZ, BYTES, JSON, UUID }

    public static CanonicalType of(Kind kind) {
        return new CanonicalType(kind, 0, 0);
    }
}
