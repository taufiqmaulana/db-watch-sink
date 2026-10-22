package io.datasync.api;

public record Column(String name, CanonicalType type, boolean nullable) {

    public Column withName(String newName) {
        return new Column(newName, type, nullable);
    }
}
