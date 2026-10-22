package io.datasync.server.api;

import io.datasync.core.Errors;
import io.quarkus.logging.Log;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Every error is a short human message plus, when useful, the technical detail behind it. */
public class ApiErrors {

    public record ErrorBody(String message, @Schema(nullable = true) String detail) {}

    public static ClientErrorException conflict(String message) {
        return new ClientErrorException(message, Response.Status.CONFLICT);
    }

    /** {@code value} trimmed, or a 400 naming the missing field. */
    public static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public static int range(int value, int min, int max, String field) {
        if (value < min || value > max) throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        return value;
    }

    @ServerExceptionMapper
    public Response webApplication(WebApplicationException e) {
        return error(e.getResponse().getStatus(), new ErrorBody(e.getMessage(), null));
    }

    @ServerExceptionMapper
    public Response badRequest(IllegalArgumentException e) {
        return error(400, new ErrorBody(e.getMessage(), null));
    }

    @ServerExceptionMapper
    public Response unexpected(RuntimeException e) {
        Log.error("request failed", e);
        return error(500, new ErrorBody(e.getMessage() == null ? "Unexpected error" : e.getMessage(), Errors.describe(e)));
    }

    private static Response error(int status, ErrorBody body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
