package io.datasync.api;

import java.util.Map;

/** A named connection. {@code props} holds url, username, password and connector-specific options. */
public record ConnectionConfig(String name, String kind, Map<String, String> props) {

    public String require(String prop) {
        String v = props.get(prop);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("connection '" + name + "': missing '" + prop + "'");
        return v;
    }
}
