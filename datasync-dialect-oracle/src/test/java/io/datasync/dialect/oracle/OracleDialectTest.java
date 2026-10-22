package io.datasync.dialect.oracle;

import io.datasync.api.CanonicalType;
import io.datasync.api.CanonicalType.Kind;
import io.datasync.jdbc.JdbcColumn;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;

class OracleDialectTest {

    private final OracleDialect dialect = new OracleDialect();

    @Test
    void mapsNumbersByPrecisionAndScale() {
        assertThat(type("NUMBER", 9, 0)).isEqualTo(CanonicalType.of(Kind.INT32));
        assertThat(type("NUMBER", 18, 0)).isEqualTo(CanonicalType.of(Kind.INT64));
        assertThat(type("NUMBER", 12, 2)).isEqualTo(new CanonicalType(Kind.DECIMAL, 12, 2));
        assertThat(type("NUMBER", 0, -127)).isEqualTo(CanonicalType.of(Kind.DECIMAL));
    }

    @Test
    void mapsDateToTimestampWithoutFraction() {
        assertThat(type("DATE", 7, null)).isEqualTo(new CanonicalType(Kind.TIMESTAMP, 0, 0));
        assertThat(type("TIMESTAMP(6)", 11, 6)).isEqualTo(new CanonicalType(Kind.TIMESTAMP, 6, 0));
        assertThat(type("TIMESTAMP(6) WITH TIME ZONE", 13, 6)).isEqualTo(CanonicalType.of(Kind.TIMESTAMP_TZ));
        assertThat(type("VARCHAR2", 100, null)).isEqualTo(CanonicalType.of(Kind.STRING));
    }

    @Test
    void booleanFlagIsYesNo() {
        assertThat(dialect.nativeType(CanonicalType.of(Kind.BOOLEAN))).isEqualTo("CHAR(1)");
        assertThat(dialect.booleanLiteral(true)).isEqualTo("'Y'");
    }

    private CanonicalType type(String name, int size, Integer digits) {
        return dialect.toCanonical(new JdbcColumn("C", Types.OTHER, name, size, digits, true));
    }
}
