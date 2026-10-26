package io.datasync.dialect.postgres;

import io.datasync.api.ObjectRef;
import io.datasync.api.ReadSpec;
import io.datasync.api.WatermarkSpec;
import io.datasync.jdbc.SqlDialect;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresDialectTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @Test
    void keysetQueryExpandsTheRowComparison() {
        ReadSpec spec = new ReadSpec(ObjectRef.parse("src.orders"), List.of("a", "b", "v"), List.of("a", "b"), 100, 500, Duration.ofSeconds(5));

        assertThat(dialect.keysetQuery(spec, false))
            .isEqualTo("SELECT \"a\", \"b\", \"v\" FROM \"src\".\"orders\" ORDER BY \"a\", \"b\" FETCH FIRST 500 ROWS ONLY");
        assertThat(dialect.keysetQuery(spec, true))
            .isEqualTo("SELECT \"a\", \"b\", \"v\" FROM \"src\".\"orders\" WHERE (\"a\" > ?) OR (\"a\" = ? AND \"b\" > ?)"
                + " ORDER BY \"a\", \"b\" FETCH FIRST 500 ROWS ONLY");
        assertThat(SqlDialect.keysetBinds(spec, List.of(1, 2))).containsExactly(1, 1, 2);
    }

    @Test
    void watermarkScanFiltersFromTheBoundAndOrdersByTheWatermarkFirst() {
        ReadSpec spec = new ReadSpec(ObjectRef.parse("t"), List.of("id", "ts"), List.of("ts", "id"), 100, 500, Duration.ofSeconds(5),
            new WatermarkSpec("ts", 42L));

        assertThat(dialect.keysetQuery(spec, true)).isEqualTo("SELECT \"id\", \"ts\" FROM \"t\" WHERE \"ts\" IS NOT NULL AND \"ts\" >= ?"
            + " AND ((\"ts\" > ?) OR (\"ts\" = ? AND \"id\" > ?)) ORDER BY \"ts\", \"id\" FETCH FIRST 500 ROWS ONLY");
        assertThat(SqlDialect.keysetBinds(spec, List.of(50L, 7))).containsExactly(42L, 50L, 50L, 7);
        assertThat(SqlDialect.keysetBinds(spec, null)).containsExactly(42L);
    }

    @Test
    void upsertUpdatesNonKeyColumns() {
        assertThat(dialect.upsertSql(ObjectRef.parse("public.t"), "\"stg\"", List.of("id", "name"), List.of("id")))
            .isEqualTo("INSERT INTO \"public\".\"t\" (\"id\", \"name\") SELECT \"id\", \"name\" FROM \"stg\""
                + " ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\"");
    }

    @Test
    void copyValuesAreEscapedForTextFormat() {
        assertThat(PgCopyLoader.copyValue(null)).isEqualTo("\\N");
        assertThat(PgCopyLoader.copyValue(true)).isEqualTo("t");
        assertThat(PgCopyLoader.copyValue(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(PgCopyLoader.copyValue("a\tb\nc\\")).isEqualTo("a\\tb\\nc\\\\");
        assertThat(PgCopyLoader.copyValue(new byte[] {0x0a, (byte) 0xff})).isEqualTo("\\\\x0aff");
    }
}
