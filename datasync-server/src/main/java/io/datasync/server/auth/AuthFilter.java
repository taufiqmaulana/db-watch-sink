package io.datasync.server.auth;

import io.datasync.server.api.ApiErrors.ErrorBody;
import io.datasync.server.api.AuthResource;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;

/** Requires a session on every API call except {@link Public} ones, and enforces {@link Requires}. */
@Provider
public class AuthFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resource;

    @Inject
    Sessions sessions;

    @Inject
    CurrentUser currentUser;

    @Override
    public void filter(ContainerRequestContext request) {
        Method method = resource.getResourceMethod();
        if (method == null || method.isAnnotationPresent(Public.class)) return;

        Cookie cookie = request.getCookies().get(Sessions.COOKIE);
        Sessions.Session session = sessions.find(cookie == null ? null : cookie.getValue()).orElse(null);
        if (session == null) {
            abort(request, Response.Status.UNAUTHORIZED, "Sign in to continue");
            return;
        }
        if (session.mustChangePassword() && resource.getResourceClass() != AuthResource.class) {
            abort(request, Response.Status.FORBIDDEN, "Change your password to continue");
            return;
        }
        Requires requires = method.getAnnotation(Requires.class);
        Role needed = requires != null ? requires.value() : request.getMethod().equals("GET") ? Role.VIEWER : Role.ADMIN;
        if (!session.role().atLeast(needed)) {
            abort(request, Response.Status.FORBIDDEN, "This needs the " + needed.name().toLowerCase() + " role");
            return;
        }
        currentUser.set(session);
    }

    private static void abort(ContainerRequestContext request, Response.Status status, String message) {
        request.abortWith(Response.status(status).type(MediaType.APPLICATION_JSON).entity(new ErrorBody(message, null)).build());
    }
}
