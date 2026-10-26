package io.datasync.api;

/**
 * Limits a read to rows changed since a point: {@code column} is not null and, when {@code from} is not null,
 * at or after it. {@code from} is already adjusted for the overlap window and has the column's canonical Java
 * type (LocalDateTime, OffsetDateTime, LocalDate, Long or BigDecimal).
 */
public record WatermarkSpec(String column, Object from) {}
