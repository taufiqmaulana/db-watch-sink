package io.datasync.core;

import io.datasync.api.CanonicalType.Kind;
import io.datasync.api.Column;
import io.datasync.api.ObjectSchema;
import io.datasync.core.config.DataSyncConfig.ColumnsDef;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The selected source columns, their target names and types. Key columns are always selected. With
 * {@code include: "*"}, BYTES columns are left out unless listed by name. {@code coerce} converts columns to
 * another type; with {@code emptyToNull}, '' in non-key text columns becomes NULL. Both happen in
 * {@link #normalize}, before hashing, so the index and every sink see the same values (design 4.4).
 */
record ColumnMapping(List<String> sourceColumns, List<String> sourceKeys, Map<String, String> rename,
                     Map<String, Kind> coerce, Set<String> emptyToNull, ObjectSchema target) {

    static ColumnMapping resolve(ObjectSchema source, ColumnsDef def, List<String> keys, boolean emptyToNull) {
        List<String> keyCols = keys.isEmpty() ? source.keyColumns() : keys;
        if (keyCols.isEmpty()) throw new IllegalArgumentException(source.ref() + " has no primary key: set 'keys' on the job");

        Map<String, Column> byName = source.columns().stream().collect(Collectors.toMap(Column::name, c -> c));
        Stream.of(keyCols, def.include(), def.exclude(), def.rename().keySet(), def.coerce().keySet())
            .flatMap(Collection::stream)
            .filter(name -> !name.equals("*") && !byName.containsKey(name))
            .findFirst()
            .ifPresent(name -> {
                throw new IllegalArgumentException("column " + name + " does not exist in " + source.ref());
            });
        def.coerce().forEach((name, to) -> {
            if (!Coercion.TARGETS.contains(to) || byName.get(name).type().kind() == Kind.BYTES) {
                throw new IllegalArgumentException("column " + name + " cannot be converted to " + to);
            }
        });

        boolean all = def.include().contains("*");
        List<Column> selected = source.columns().stream()
            .filter(c -> keyCols.contains(c.name()) || !def.exclude().contains(c.name())
                && (all ? c.type().kind() != Kind.BYTES || def.include().contains(c.name())
                        : def.include().contains(c.name())))
            .map(c -> def.coerce().containsKey(c.name()) ? new Column(c.name(), Coercion.type(def.coerce().get(c.name())), c.nullable()) : c)
            .toList();

        Map<String, String> rename = def.rename();
        Set<String> blankable = !emptyToNull ? Set.of() : selected.stream()
            .filter(c -> !keyCols.contains(c.name()) && (c.type().kind() == Kind.STRING || c.type().kind() == Kind.JSON))
            .map(Column::name)
            .collect(Collectors.toSet());
        ObjectSchema target = new ObjectSchema(source.ref(),
            selected.stream().map(c -> c.withName(rename.getOrDefault(c.name(), c.name()))).toList(),
            keyCols.stream().map(k -> rename.getOrDefault(k, k)).toList());
        return new ColumnMapping(selected.stream().map(Column::name).toList(), List.copyOf(keyCols), rename,
            Map.copyOf(def.coerce()), blankable, target);
    }

    /** The source row with coercions and the empty-string rule applied; other columns are kept as read. */
    Map<String, Object> normalize(Map<String, Object> sourceRow) {
        if (coerce.isEmpty() && emptyToNull.isEmpty()) return sourceRow;
        Map<String, Object> out = new LinkedHashMap<>(sourceRow);
        coerce.forEach((column, to) -> out.put(column, Coercion.convert(out.get(column), to, column)));
        emptyToNull.forEach(column -> {
            if ("".equals(out.get(column))) out.put(column, null);
        });
        return out;
    }

    /** The selected columns of a source row, under their target names. */
    Map<String, Object> row(Map<String, Object> sourceRow) {
        return pick(sourceRow, sourceColumns);
    }

    /** The key columns of a source row or key, under their target names. */
    Map<String, Object> key(Map<String, Object> sourceRow) {
        return pick(sourceRow, sourceKeys);
    }

    private Map<String, Object> pick(Map<String, Object> sourceRow, List<String> columns) {
        Map<String, Object> out = new LinkedHashMap<>();
        columns.forEach(c -> out.put(rename.getOrDefault(c, c), sourceRow.get(c)));
        return out;
    }
}
