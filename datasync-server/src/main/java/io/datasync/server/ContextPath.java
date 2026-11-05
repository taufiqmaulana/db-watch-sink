package io.datasync.server;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Serves the whole app (UI, API, health checks, metrics) under a context path chosen at run time, such as /datasync,
 * for proxies that forward the full path instead of stripping it. Quarkus' own quarkus.http.root-path is fixed at
 * build time, so it cannot be set on a prebuilt image.
 *
 * <p>A request under the context path is rerouted with the prefix removed; the bare context path redirects to itself
 * with a trailing slash, so the UI's relative URLs resolve. Requests outside it are served as before, which keeps
 * container health checks on /q/health working.
 */
@Singleton
public class ContextPath {

    /** Segments of URL-safe characters, as accepted in DATASYNC_CONTEXT_PATH and X-Forwarded-Prefix. */
    private static final Pattern PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)*/?");

    private static final String REROUTED = ContextPath.class.getName() + ".rerouted";

    private final String path;

    public ContextPath(@ConfigProperty(name = "datasync.context-path") Optional<String> configured) {
        String value = configured.map(String::trim).orElse("");
        if (!value.startsWith("/")) value = "/" + value;
        if (!PATH.matcher(value).matches()) {
            throw new IllegalStateException("DATASYNC_CONTEXT_PATH must be a path such as /datasync, got '" + configured.orElse("") + "'");
        }
        this.path = join(value);
    }

    /** The context path without a trailing slash, or "/" when the app is served at the root. */
    public String path() {
        return path;
    }

    /**
     * The path the browser sees the app under: the proxy's X-Forwarded-Prefix (when it strips a prefix of its own)
     * followed by the context path. Used to scope the session cookie to this app.
     */
    public String publicPath(String forwardedPrefix) {
        String prefix = forwardedPrefix == null ? "" : forwardedPrefix.trim();
        return join(PATH.matcher(prefix).matches() ? prefix : "", path);
    }

    /** Joins paths into one with a leading slash and no trailing one; "/" when all are empty. */
    static String join(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            for (String segment : part.split("/")) {
                if (!segment.isEmpty()) joined.append('/').append(segment);
            }
        }
        return joined.isEmpty() ? "/" : joined.toString();
    }

    void install(@Observes StartupEvent ignored, Router router) {
        if (path.equals("/")) return;
        router.route().order(Integer.MIN_VALUE).handler(rc -> {
            String requested = rc.normalizedPath();
            if (rc.get(REROUTED) != null) {
                rc.next();
            } else if (requested.equals(path)) {
                // relative, so it stays right under any prefix a proxy adds in front: /datasync -> datasync/
                String query = rc.request().query();
                String location = path.substring(path.lastIndexOf('/') + 1) + "/" + (query == null ? "" : "?" + query);
                rc.response().setStatusCode(302).putHeader("Location", location).end();
            } else if (requested.startsWith(path + "/")) {
                rc.put(REROUTED, true);
                String uri = rc.request().uri();
                String query = rc.request().query();
                rc.reroute(uri.startsWith(path + "/") ? uri.substring(path.length())
                    : requested.substring(path.length()) + (query == null ? "" : "?" + query));
            } else {
                rc.next();
            }
        });
    }
}
