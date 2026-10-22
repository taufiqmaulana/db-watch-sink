package io.datasync.dialect.postgres;

import io.datasync.api.CanonicalType;
import io.datasync.api.CanonicalType.Kind;
import io.datasync.api.ObjectRef;
import io.datasync.jdbc.BulkLoader;
import io.datasync.jdbc.JdbcColumn;
import io.datasync.jdbc.SqlDialect;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class PostgresDialect implements SqlDialect {

    private final BulkLoader loader = new PgCopyLoader(this);

    @Override
    public String vendor() {
        return "postgres";
    }

    @Override
    public String normalizeIdentifier(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    @Override
    public CanonicalType toCanonical(JdbcColumn col) {
        return switch (col.typeName().toLowerCase(Locale.ROOT)) {
            case "bool" -> CanonicalType.of(Kind.BOOLEAN);
            case "int2", "int4", "serial", "smallserial" -> CanonicalType.of(Kind.INT32);
            case "int8", "bigserial" -> CanonicalType.of(Kind.INT64);
            // pgjdbc reports unconstrained numeric with a size of 0 or 1000+
            case "numeric" -> col.size() == 0 || col.size() >= 1000
                ? CanonicalType.of(Kind.DECIMAL)
                : new CanonicalType(Kind.DECIMAL, col.size(), col.digits() == null ? 0 : col.digits());
            case "float4", "float8" -> CanonicalType.of(Kind.FLOAT64);
            case "date" -> CanonicalType.of(Kind.DATE);
            case "timestamp" -> new CanonicalType(Kind.TIMESTAMP, col.digits() == null ? 6 : col.digits(), 0);
            case "timestamptz" -> CanonicalType.of(Kind.TIMESTAMP_TZ);
            case "bytea" -> CanonicalType.of(Kind.BYTES);
            case "json", "jsonb" -> CanonicalType.of(Kind.JSON);
            case "uuid" -> CanonicalType.of(Kind.UUID);
            default -> CanonicalType.of(Kind.STRING);
        };
    }

    @Override
    public String nativeType(CanonicalType t) {
        return switch (t.kind()) {
            case BOOLEAN -> "boolean";
            case INT32 -> "integer";
            case INT64 -> "bigint";
            case DECIMAL -> t.precision() == 0 ? "numeric" : "numeric(" + t.precision() + "," + t.scale() + ")";
            case FLOAT64 -> "double precision";
            case STRING -> "text";
            case DATE -> "date";
            case TIMESTAMP -> "timestamp(" + Math.min(t.precision(), 6) + ")";
            case TIMESTAMP_TZ -> "timestamptz";
            case BYTES -> "bytea";
            case JSON -> "jsonb";
            case UUID -> "uuid";
        };
    }

    @Override
    public String booleanLiteral(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    @Override
    public String currentTimeSql() {
        return "now()";
    }

    @Override
    public String upsertSql(ObjectRef target, String staging, List<String> cols, List<String> keys) {
        String colList = cols.stream().map(this::quote).collect(Collectors.joining(", "));
        String updates = cols.stream().filter(c -> !keys.contains(c)).map(this::quote)
            .map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "));
        return "INSERT INTO " + qualify(target) + " (" + colList + ") SELECT " + colList + " FROM " + staging
            + " ON CONFLICT (" + keys.stream().map(this::quote).collect(Collectors.joining(", ")) + ")"
            + (updates.isEmpty() ? " DO NOTHING" : " DO UPDATE SET " + updates);
    }

    @Override
    public BulkLoader bulkLoader() {
        return loader;
    }
}
