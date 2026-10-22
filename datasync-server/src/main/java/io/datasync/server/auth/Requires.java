package io.datasync.server.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The minimum role for an endpoint. Without it, GET needs VIEWER and everything else needs ADMIN. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Requires {
    Role value();
}
