package io.datasync.sink.webhook;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.datasync.api.ApplyResult;
import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.ConnectionConfig;
import io.datasync.api.HealthStatus;
import io.datasync.api.ObjectSchema;
import io.datasync.api.Sink;
import io.datasync.api.SinkCapabilities;
import io.datasync.api.SinkContext;
import io.datasync.api.SinkException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Posts change batches as JSON (design 8.2). Success is any 2xx; 429 and 5xx, timeouts and refused connections are
 * retryable (honouring Retry-After); other responses are permanent and go to the dead letters. Every request carries
 * an Idempotency-Key, X-DataSync-Job and, with HMAC auth, X-DataSync-Signature: sha256=hex(HMAC-SHA256(secret, body)).
 * <p>
 * Settings come from the connection (url, authType, username, password) and the sink config, which may also give
 * them inline for the CLI: {@code url}, {@code method} (POST, PUT), {@code headers}, {@code timeoutSeconds},
 * {@code batch: {mode: per-batch|per-row, maxRows}}, {@code auth: {type: none|bearer|basic|hmac, username, password}}
 * and {@code deleteAs: delete|upsert-with-flag}.
 */
final class WebhookSink implements Sink {

    enum AuthType { NONE, BEARER, BASIC, HMAC }

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private final ConnectionConfig connection;
    private HttpClient client;
    private URI url;
    private String method;
    private Duration timeout;
    private Map<String, String> headers;
    private AuthType authType;
    private String username;
    private String secret;
    private int maxRows;
    private boolean perRow;
    private boolean deletesAsFlag;
    private String deleteFlag;
    private String jobName;

    WebhookSink(ConnectionConfig connection) {
        this.connection = connection;
    }

    @Override
    public String type() {
        return "webhook";
    }

    @Override
    public void init(SinkContext ctx, ObjectSchema schema) {
        Map<String, Object> cfg = ctx.config();
        Map<String, String> conn = connection == null ? Map.of() : connection.props();
        Map<?, ?> auth = cfg.get("auth") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> batch = cfg.get("batch") instanceof Map<?, ?> m ? m : Map.of();

        String target = first(cfg.get("url"), conn.get("url"));
        if (target == null) throw new IllegalArgumentException("sink " + ctx.sinkId() + ": missing 'url'");
        url = URI.create(target);
        if (!List.of("http", "https").contains(String.valueOf(url.getScheme()).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("webhook URL must be http or https: " + target);
        }
        method = first(cfg.get("method"), "POST").toUpperCase(Locale.ROOT);
        if (!List.of("POST", "PUT").contains(method)) throw new IllegalArgumentException("webhook method must be POST or PUT");
        timeout = Duration.ofSeconds(Long.parseLong(first(cfg.get("timeoutSeconds"), "10")));
        headers = new LinkedHashMap<>();
        if (cfg.get("headers") instanceof Map<?, ?> h) h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
        authType = AuthType.valueOf(first(auth.get("type"), conn.get("authType"), "NONE").toUpperCase(Locale.ROOT));
        username = first(auth.get("username"), conn.get("username"));
        secret = first(auth.get("password"), auth.get("secret"), auth.get("token"), conn.get("password"));
        if (authType != AuthType.NONE && secret == null) throw new IllegalArgumentException("webhook auth " + authType + " needs a secret");
        perRow = "per-row".equalsIgnoreCase(first(batch.get("mode"), "per-batch"));
        maxRows = Integer.parseInt(first(batch.get("maxRows"), "500"));
        deletesAsFlag = "upsert-with-flag".equalsIgnoreCase(first(cfg.get("deleteAs"), "delete"));
        deleteFlag = first(cfg.get("deleteFlagField"), "_is_deleted");
        // the server passes the job's name, since its jobs run under their ids
        jobName = first(cfg.get("jobName"), ctx.jobId());
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public ApplyResult apply(ChangeBatch batch) {
        AddressGuard.check(url);
        byte[] body = envelope(batch);
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey(batch))
            .header("X-DataSync-Job", jobName)
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        switch (authType) {
            case BEARER -> request.header("Authorization", "Bearer " + secret);
            case BASIC -> request.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((Objects.requireNonNullElse(username, "") + ":" + secret).getBytes(StandardCharsets.UTF_8)));
            case HMAC -> request.header("X-DataSync-Signature", "sha256=" + hmac(body));
            case NONE -> { }
        }
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw SinkException.retryable("cannot reach " + url, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SinkException.retryable("interrupted while posting to " + url, null, e);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            long deletes = batch.changes().stream().filter(c -> c.op() == Change.Op.DELETE).count();
            return new ApplyResult(batch.changes().size() - deletes, deletes);
        }
        String message = "HTTP " + status + " from " + url + snippet(response.body());
        if (status == 429 || status >= 500) {
            throw SinkException.retryable(message, retryAfter(response.headers().firstValue("Retry-After").orElse(null)), null);
        }
        throw SinkException.permanent(message, null);
    }

    /** One request per change in per-row mode, else up to maxRows; the engine splits batches to this. */
    @Override
    public SinkCapabilities capabilities() {
        return new SinkCapabilities(true, false, true, perRow ? 1 : maxRows, false, deletesAsFlag);
    }

    /** Reachable when the URL answers at all: even a 405 to HEAD means the consumer is there. */
    @Override
    public HealthStatus health() {
        try {
            AddressGuard.check(url);
            HttpResponse<Void> response = client.send(HttpRequest.newBuilder(url).timeout(timeout)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            return new HealthStatus(true, "HTTP " + response.statusCode());
        } catch (IOException | RuntimeException e) {
            return new HealthStatus(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HealthStatus(false, "interrupted");
        }
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }

    /** The default envelope of design 8.2. */
    private byte[] envelope(ChangeBatch batch) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("job", jobName);
        envelope.put("batchId", batch.batchId());
        envelope.put("object", batch.schema().ref().toString());
        envelope.put("changes", batch.changes().stream().map(this::change).toList());
        try {
            return JSON.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            throw SinkException.permanent("cannot write the payload: " + e.getMessage(), e);
        }
    }

    /** A delete is sent as op "delete", or with deleteAs: upsert-with-flag as the last known row with the flag set. */
    private Map<String, Object> change(Change c) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean flagged = c.op() == Change.Op.DELETE && deletesAsFlag;
        out.put("op", c.op() == Change.Op.UPSERT || flagged ? "upsert" : "delete");
        out.put("key", c.key());
        if (c.op() == Change.Op.UPSERT) {
            out.put("row", c.row());
        } else if (flagged) {
            Map<String, Object> row = new LinkedHashMap<>(c.row() != null ? c.row() : c.key());
            row.put(deleteFlag, true);
            out.put("row", row);
        }
        out.put("idempotencyKey", c.idempotencyKey());
        if (c.observedAt() != null) out.put("observedAt", c.observedAt());
        return out;
    }

    /** The change's own key for a single change, else a digest of the batch's keys, so a re-sent batch repeats it. */
    private static String idempotencyKey(ChangeBatch batch) {
        if (batch.changes().size() == 1) return batch.changes().getFirst().idempotencyKey();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            batch.changes().forEach(c -> sha.update(c.idempotencyKey().getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(sha.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Retry-After as seconds or an HTTP date; null when absent or unreadable. */
    static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            try {
                Duration wait = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (RuntimeException unreadable) {
                return null;
            }
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) return "";
        String text = body.strip().replaceAll("\\s+", " ");
        return ": " + (text.length() > 200 ? text.substring(0, 200) + "…" : text);
    }

    private static String first(Object... values) {
        for (Object v : values) {
            if (v != null && !v.toString().isBlank()) return v.toString().trim();
        }
        return null;
    }
}
