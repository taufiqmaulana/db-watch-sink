package io.datasync.jdbc;

import io.datasync.api.CanonicalType;
import io.datasync.api.ObjectRef;
import io.datasync.api.ReadSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Everything vendor-specific about a JDBC database. Implementations are found with {@link java.util.ServiceLoader}. */
public interface SqlDialect {

    /** Matches the suffix of the connection kind, e.g. "oracle" for "jdbc-oracle". */
    String vendor();

    /** Folds an identifier to the vendor's unquoted case (used for created objects). */
    String normalizeIdentifier(String id);

    CanonicalType toCanonical(JdbcColumn col);

    String nativeType(CanonicalType t);

    /** The type of a primary-key column, for vendors that cannot index every type (Oracle LOBs). */
    default String nativeKeyType(CanonicalType t) {
        return nativeType(t);
    }

    /** True when the vendor stores '' as NULL (Oracle), so created string columns must stay nullable. */
    default boolean emptyStringIsNull() {
        return false;
    }

    String booleanLiteral(boolean value);

    String currentTimeSql();

    /** Inserts or updates {@code target} from {@code staging}, matching on {@code keys}. */
    String upsertSql(ObjectRef target, String staging, List<String> cols, List<String> keys);

    BulkLoader bulkLoader();

    default String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    default String qualify(ObjectRef ref) {
        return ref.schema() == null ? quote(ref.name()) : quote(ref.schema()) + "." + quote(ref.name());
    }

    /** One chunk of a keyset scan: {@link #keysetScan} limited to {@code chunkSize} rows. */
    default String keysetQuery(ReadSpec spec, boolean afterKey) {
        return keysetScan(spec, afterKey) + " FETCH FIRST " + spec.chunkSize() + " ROWS ONLY";
    }

    /**
     * The scan in key order, without a row limit. A watermark adds {@code wm IS NOT NULL} and, with a lower
     * bound, {@code wm >= ?}. With {@code afterKey}, the keyset condition takes one bind per key prefix, written
     * out because Oracle has no row-value comparison. {@link #keysetBinds} lists the binds in order.
     */
    default String keysetScan(ReadSpec spec, boolean afterKey) {
        List<String> keys = spec.keyColumns().stream().map(this::quote).toList();
        List<String> conditions = new ArrayList<>();
        if (spec.watermark() != null) {
            String wm = quote(spec.watermark().column());
            conditions.add(wm + " IS NOT NULL");
            if (spec.watermark().from() != null) conditions.add(wm + " >= ?");
        }
        if (afterKey) {
            String keyset = IntStream.range(0, keys.size())
                .mapToObj(i -> "(" + IntStream.rangeClosed(0, i)
                    .mapToObj(j -> keys.get(j) + (j < i ? " = ?" : " > ?"))
                    .collect(Collectors.joining(" AND ")) + ")")
                .collect(Collectors.joining(" OR "));
            conditions.add(conditions.isEmpty() ? keyset : "(" + keyset + ")");
        }
        StringBuilder sql = new StringBuilder("SELECT ")
            .append(spec.columns().stream().map(this::quote).collect(Collectors.joining(", ")))
            .append(" FROM ").append(qualify(spec.object()));
        if (!conditions.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conditions));
        return sql.append(" ORDER BY ").append(String.join(", ", keys)).toString();
    }

    /** Bind values for {@link #keysetQuery}: the watermark bound if any, then per key prefix k1, k1 k2, k1 k2 k3, ... */
    static List<Object> keysetBinds(ReadSpec spec, List<Object> lastKey) {
        List<Object> binds = new ArrayList<>();
        if (spec.watermark() != null && spec.watermark().from() != null) binds.add(spec.watermark().from());
        if (lastKey != null) {
            for (int i = 0; i < lastKey.size(); i++) binds.addAll(lastKey.subList(0, i + 1));
        }
        return binds;
    }
}
