package io.datasync.jdbc;

import io.datasync.api.CanonicalType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

final class JdbcValues {

    private JdbcValues() {}

    /** Reads column {@code i} as the canonical Java type of {@code type}. */
    static Object read(ResultSet rs, int i, CanonicalType type) throws SQLException {
        Object v = switch (type.kind()) {
            case BOOLEAN -> rs.getBoolean(i);
            case INT32 -> rs.getInt(i);
            case INT64 -> rs.getLong(i);
            case DECIMAL -> rs.getBigDecimal(i);
            case FLOAT64 -> rs.getDouble(i);
            case STRING, JSON -> rs.getString(i);
            case DATE -> rs.getObject(i, LocalDate.class);
            case TIMESTAMP -> rs.getObject(i, LocalDateTime.class);
            case TIMESTAMP_TZ -> rs.getObject(i, OffsetDateTime.class);
            case BYTES -> rs.getBytes(i);
            case UUID -> rs.getObject(i, UUID.class);
        };
        return rs.wasNull() ? null : v;
    }
}
