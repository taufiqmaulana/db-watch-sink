package io.datasync.core;

import io.datasync.api.CanonicalType;
import io.datasync.api.CanonicalType.Kind;
import io.datasync.api.Column;
import io.datasync.api.ObjectRef;
import io.datasync.api.ObjectSchema;
import io.datasync.core.config.DataSyncConfig.ColumnsDef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnMappingTest {

    private static final ObjectSchema SCHEMA = new ObjectSchema(ObjectRef.parse("HR.T"), List.of(
        new Column("ID", CanonicalType.of(Kind.STRING), false),
        new Column("ACTIVE", CanonicalType.of(Kind.INT32), true),
        new Column("NOTE", CanonicalType.of(Kind.STRING), true),
        new Column("SEEN", CanonicalType.of(Kind.STRING), true)), List.of("ID"));

    private static Map<String, Object> row(Object id, Object active, Object note, Object seen) {
        Map<String, Object> row = new HashMap<>();
        row.put("ID", id);
        row.put("ACTIVE", active);
        row.put("NOTE", note);
        row.put("SEEN", seen);
        return row;
    }

    @Test
    void coercesColumnsAndGivesTheTargetTheNewTypes() {
        ColumnMapping m = ColumnMapping.resolve(SCHEMA,
            new ColumnsDef(null, null, null, Map.of("ACTIVE", Kind.BOOLEAN, "SEEN", Kind.TIMESTAMP), null), List.of(), false);

        assertThat(m.target().columns()).extracting(c -> c.type().kind()).containsExactly(Kind.STRING, Kind.BOOLEAN, Kind.STRING, Kind.TIMESTAMP);
        assertThat(m.normalize(row("1", 1, "x", "2026-01-02 03:04:05.5")))
            .containsEntry("ACTIVE", true).containsEntry("SEEN", LocalDateTime.of(2026, 1, 2, 3, 4, 5, 500_000_000));
        assertThat(m.normalize(row("1", 0, "", null))).containsEntry("ACTIVE", false).containsEntry("NOTE", "").containsEntry("SEEN", null);
    }

    @Test
    void emptyToNullChangesTextButNeverKeys() {
        ColumnMapping m = ColumnMapping.resolve(SCHEMA, new ColumnsDef(null, null, null), List.of(), true);
        assertThat(m.normalize(row("", 1, "", "x"))).containsEntry("ID", "").containsEntry("NOTE", null).containsEntry("SEEN", "x");
    }

    @Test
    void convertsCommonForms() {
        assertThat(Coercion.convert("Y", Kind.BOOLEAN, "c")).isEqualTo(true);
        assertThat(Coercion.convert("false", Kind.BOOLEAN, "c")).isEqualTo(false);
        assertThat(Coercion.convert(new BigDecimal("42"), Kind.INT64, "c")).isEqualTo(42L);
        assertThat(Coercion.convert("12.50", Kind.DECIMAL, "c")).isEqualTo(new BigDecimal("12.50"));
        assertThat(Coercion.convert(LocalDateTime.of(2026, 1, 2, 3, 4), Kind.DATE, "c")).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(Coercion.convert("2026-01-02T03:04:00+07:00", Kind.TIMESTAMP, "c")).isEqualTo(LocalDateTime.of(2026, 1, 1, 20, 4));
        assertThat(Coercion.convert(LocalDateTime.of(2026, 1, 2, 3, 4), Kind.TIMESTAMP_TZ, "c")).isEqualTo(OffsetDateTime.parse("2026-01-02T03:04Z"));
        assertThat(Coercion.convert(new BigDecimal("1.50"), Kind.STRING, "c")).isEqualTo("1.5");
    }

    @Test
    void rejectsWhatCannotBeConverted() {
        assertThatThrownBy(() -> Coercion.convert("maybe", Kind.BOOLEAN, "ACTIVE")).hasMessageStartingWith("cannot convert ACTIVE value 'maybe' to BOOLEAN");
        assertThatThrownBy(() -> Coercion.convert(new BigDecimal("1.5"), Kind.INT32, "N")).hasMessageContaining("to INT32");
        assertThatThrownBy(() -> ColumnMapping.resolve(SCHEMA, new ColumnsDef(null, null, null, Map.of("NOTE", Kind.BYTES), null), List.of(), false))
            .hasMessage("column NOTE cannot be converted to BYTES");
        assertThatThrownBy(() -> ColumnMapping.resolve(SCHEMA, new ColumnsDef(null, null, null, Map.of("NOPE", Kind.INT32), null), List.of(), false))
            .hasMessageContaining("column NOPE does not exist");
    }
}
