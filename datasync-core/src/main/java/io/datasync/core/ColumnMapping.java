package io.datasync.core;

import io.datasync.api.CanonicalType;
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
 * The selected source columns and their target names. Key columns are always selected. With
 * {@code include: "*"}, BYTES columns are left out unless listed by name.
 */
record ColumnMapping(List<String> sourceColumns, List<String> sourceKeys, Map<String, String> rename, ObjectSchema target) {

    static ColumnMapping resolve(ObjectSchema source, ColumnsDef def, List<String> keys) {
        List<String> keyCols = keys.isEmpty() ? source.keyColumns() : keys;
        if (keyCols.isEmpty()) throw new IllegalArgumentException(source.ref() + " has no primary key: set 'keys' on the job");

        Set<String> known = source.columns().stream().map(Column::name).collect(Collectors.toSet());
        Stream.of(keyCols, def.include(), def.exclude(), def.rename().keySet())
            .flatMap(Collection::stream)
            .filter(name -> !name.equals("*") && !known.contains(name))
            .findFirst()
            .ifPresent(name -> {
                throw new IllegalArgumentException("column " + name + " does not exist in " + source.ref());
            });

        boolean all = def.include().contains("*");
        List<Column> selected = source.columns().stream()
            .filter(c -> keyCols.contains(c.name()) || !def.exclude().contains(c.name())
                && (all ? c.type().kind() != CanonicalType.Kind.BYTES || def.include().contains(c.name())
                        : def.include().contains(c.name())))
            .toList();

        Map<String, String> rename = def.rename();
        ObjectSchema target = new ObjectSchema(source.ref(),
            selected.stream().map(c -> c.withName(rename.getOrDefault(c.name(), c.name()))).toList(),
            keyCols.stream().map(k -> rename.getOrDefault(k, k)).toList());
        return new ColumnMapping(selected.stream().map(Column::name).toList(), List.copyOf(keyCols), rename, target);
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
