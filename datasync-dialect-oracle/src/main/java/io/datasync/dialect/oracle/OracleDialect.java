package io.datasync.dialect.oracle;

import io.datasync.api.CanonicalType;
import io.datasync.api.CanonicalType.Kind;
import io.datasync.api.ObjectRef;
import io.datasync.api.ReadSpec;
import io.datasync.jdbc.BulkLoader;
import io.datasync.jdbc.JdbcColumn;
import io.datasync.jdbc.SqlDialect;

import java.util.List;
import java.util.Locale;

/** Oracle 11g and later. Source side only for now; the sink side (MERGE, array-bound staging) arrives in M5. */
public final class OracleDialect implements SqlDialect {

    @Override
    public String vendor() {
        return "oracle";
    }

    @Override
    public String normalizeIdentifier(String id) {
        return id.toUpperCase(Locale.ROOT);
    }

    /** ROWNUM over an ordered inline view: FETCH FIRST needs 12c, this works on every version. */
    @Override
    public String keysetQuery(ReadSpec spec, boolean afterKey) {
        return "SELECT * FROM (" + keysetScan(spec, afterKey) + ") WHERE ROWNUM <= " + spec.chunkSize();
    }

    @Override
    public CanonicalType toCanonical(JdbcColumn col) {
        String type = col.typeName().toUpperCase(Locale.ROOT);
        if (type.equals("NUMBER")) return number(col.size(), col.digits());
        if (type.equals("DATE")) return new CanonicalType(Kind.TIMESTAMP, 0, 0);
        if (type.startsWith("TIMESTAMP")) {
            return type.contains("TIME ZONE")
                ? CanonicalType.of(Kind.TIMESTAMP_TZ)
                : new CanonicalType(Kind.TIMESTAMP, col.digits() == null ? 6 : col.digits(), 0);
        }
        return switch (type) {
            case "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE" -> CanonicalType.of(Kind.FLOAT64);
            case "BLOB", "RAW", "LONG RAW" -> CanonicalType.of(Kind.BYTES);
            case "JSON" -> CanonicalType.of(Kind.JSON);
            default -> CanonicalType.of(Kind.STRING);
        };
    }

    /** NUMBER without precision is reported as size 0, scale -127 and maps to unbounded DECIMAL. */
    private static CanonicalType number(int precision, Integer scale) {
        if (precision == 0 || scale == null || scale < 0) return CanonicalType.of(Kind.DECIMAL);
        if (scale == 0 && precision <= 9) return CanonicalType.of(Kind.INT32);
        if (scale == 0 && precision <= 18) return CanonicalType.of(Kind.INT64);
        return new CanonicalType(Kind.DECIMAL, precision, scale);
    }

    @Override
    public String nativeType(CanonicalType t) {
        return switch (t.kind()) {
            case BOOLEAN -> "CHAR(1)";
            case INT32 -> "NUMBER(10)";
            case INT64 -> "NUMBER(19)";
            case DECIMAL -> t.precision() == 0 ? "NUMBER" : "NUMBER(" + t.precision() + "," + t.scale() + ")";
            case FLOAT64 -> "BINARY_DOUBLE";
            case STRING, JSON -> "CLOB";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP(" + t.precision() + ")";
            case TIMESTAMP_TZ -> "TIMESTAMP WITH TIME ZONE";
            case BYTES -> "BLOB";
            case UUID -> "VARCHAR2(36)";
        };
    }

    @Override
    public String booleanLiteral(boolean value) {
        return value ? "'Y'" : "'N'";
    }

    @Override
    public String currentTimeSql() {
        return "SYSTIMESTAMP";
    }

    @Override
    public String upsertSql(ObjectRef target, String staging, List<String> cols, List<String> keys) {
        throw new UnsupportedOperationException("Oracle as a sink is planned for M5");
    }

    @Override
    public BulkLoader bulkLoader() {
        throw new UnsupportedOperationException("Oracle as a sink is planned for M5");
    }
}
