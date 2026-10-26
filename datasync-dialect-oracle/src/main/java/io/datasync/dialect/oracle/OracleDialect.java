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
import java.util.function.Function;
import java.util.stream.Collectors;

/** Oracle 11g and later, as a source and as a sink (staging in a global temporary table, then MERGE). */
public final class OracleDialect implements SqlDialect {

    /** The largest VARCHAR2 without MAX_STRING_SIZE=EXTENDED. */
    private static final int MAX_VARCHAR = 4000;

    private final BulkLoader loader = new OracleStagingLoader(this);

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
            case "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR" -> new CanonicalType(Kind.STRING, col.size(), 0);
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
            case STRING -> t.precision() > 0 && t.precision() <= MAX_VARCHAR ? "VARCHAR2(" + t.precision() + " CHAR)" : "CLOB";
            case JSON -> "CLOB";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP(" + t.precision() + ")";
            case TIMESTAMP_TZ -> "TIMESTAMP WITH TIME ZONE";
            case BYTES -> "BLOB";
            case UUID -> "VARCHAR2(36)";
        };
    }

    /** A LOB cannot be a primary key, so unbounded text keys get the largest VARCHAR2 instead. */
    @Override
    public String nativeKeyType(CanonicalType t) {
        String type = nativeType(t);
        return type.equals("CLOB") ? "VARCHAR2(" + MAX_VARCHAR + ")" : type;
    }

    @Override
    public boolean emptyStringIsNull() {
        return true;
    }

    /** Booleans are CHAR(1) holding 'Y' or 'N' (design decision 11). */
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
        Function<String, String> source = c -> "s." + quote(c);
        String on = keys.stream().map(k -> "t." + quote(k) + " = " + source.apply(k)).collect(Collectors.joining(" AND "));
        String updates = cols.stream().filter(c -> !keys.contains(c))
            .map(c -> "t." + quote(c) + " = " + source.apply(c)).collect(Collectors.joining(", "));
        return "MERGE INTO " + qualify(target) + " t USING " + staging + " s ON (" + on + ")"
            + (updates.isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET " + updates)
            + " WHEN NOT MATCHED THEN INSERT (" + cols.stream().map(this::quote).collect(Collectors.joining(", ")) + ")"
            + " VALUES (" + cols.stream().map(source).collect(Collectors.joining(", ")) + ")";
    }

    @Override
    public BulkLoader bulkLoader() {
        return loader;
    }
}
