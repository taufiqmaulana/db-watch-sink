package io.datasync.api;

/** A table or view, optionally schema-qualified. Names are kept exactly as entered. */
public record ObjectRef(String schema, String name) {

    public static ObjectRef parse(String qualified) {
        int dot = qualified.indexOf('.');
        return dot < 0 ? new ObjectRef(null, qualified)
                       : new ObjectRef(qualified.substring(0, dot), qualified.substring(dot + 1));
    }

    @Override
    public String toString() {
        return schema == null ? name : schema + "." + name;
    }
}
