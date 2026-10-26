package io.datasync.dialect.oracle;

import io.datasync.api.CanonicalType;
import io.datasync.api.CanonicalType.Kind;
import io.datasync.api.ObjectRef;
import io.datasync.api.ReadSpec;
import io.datasync.jdbc.JdbcColumn;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OracleDialectTest {

    private final OracleDialect dialect = new OracleDialect();

    @Test
    void limitsChunksWithRownumSoPre12cVersionsWork() {
        ReadSpec spec = new ReadSpec(ObjectRef.parse("HR.CUSTOMERS"), List.of("ID", "NAME"), List.of("ID"), 100, 500, Duration.ofSeconds(5));

        assertThat(dialect.keysetQuery(spec, true)).isEqualTo(
            "SELECT * FROM (SELECT \"ID\", \"NAME\" FROM \"HR\".\"CUSTOMERS\" WHERE (\"ID\" > ?) ORDER BY \"ID\") WHERE ROWNUM <= 500");
    }

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
        assertThat(type("VARCHAR2", 100, null)).isEqualTo(new CanonicalType(Kind.STRING, 100, 0));
    }

    @Test
    void mergesFromStagingAndNeverUpdatesKeys() {
        assertThat(dialect.upsertSql(ObjectRef.parse("HR.T"), "\"DS_STG_1\"", List.of("ID", "NAME"), List.of("ID"))).isEqualTo(
            "MERGE INTO \"HR\".\"T\" t USING \"DS_STG_1\" s ON (t.\"ID\" = s.\"ID\")"
                + " WHEN MATCHED THEN UPDATE SET t.\"NAME\" = s.\"NAME\""
                + " WHEN NOT MATCHED THEN INSERT (\"ID\", \"NAME\") VALUES (s.\"ID\", s.\"NAME\")");
    }

    @Test
    void boundedTextIsVarchar2AndUnboundedTextIsClobExceptAsKey() {
        assertThat(dialect.nativeType(new CanonicalType(Kind.STRING, 50, 0))).isEqualTo("VARCHAR2(50 CHAR)");
        assertThat(dialect.nativeType(CanonicalType.of(Kind.STRING))).isEqualTo("CLOB");
        assertThat(dialect.nativeKeyType(CanonicalType.of(Kind.STRING))).isEqualTo("VARCHAR2(4000)");
        assertThat(OracleStagingLoader.stagingName(ObjectRef.parse("HR.A_VERY_LONG_TABLE_NAME_INDEED"), List.of("ID")))
            .hasSizeLessThanOrEqualTo(30).isEqualTo(OracleStagingLoader.stagingName(ObjectRef.parse("HR.A_VERY_LONG_TABLE_NAME_INDEED"), List.of("ID")));
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
