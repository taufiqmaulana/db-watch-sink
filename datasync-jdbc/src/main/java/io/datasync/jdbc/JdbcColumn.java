package io.datasync.jdbc;

/** A column as reported by {@link java.sql.DatabaseMetaData#getColumns}. {@code digits} is null when not reported. */
public record JdbcColumn(String name, int jdbcType, String typeName, int size, Integer digits, boolean nullable) {}
