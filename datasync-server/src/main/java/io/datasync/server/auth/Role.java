package io.datasync.server.auth;

/** Ordered: each role can do everything the previous one can. */
public enum Role {
    VIEWER, OPERATOR, ADMIN;

    public boolean atLeast(Role other) {
        return compareTo(other) >= 0;
    }
}
