package io.datasync.sink.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.datasync.api.Change;
import io.datasync.api.ChangeBatch;
import io.datasync.api.CanonicalType;
import io.datasync.api.Column;
import io.datasync.api.ConnectionConfig;
import io.datasync.api.ObjectRef;
import io.datasync.api.ObjectSchema;
import io.datasync.api.SinkContext;
import io.datasync.api.SinkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSinkTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectSchema SCHEMA = new ObjectSchema(new ObjectRef("app", "users"),
        List.of(new Column("id", CanonicalType.of(CanonicalType.Kind.INT64), false), new Column("name", CanonicalType.of(CanonicalType.Kind.STRING), true)), List.of("id"));

    private HttpServer server;
    private final List<byte[]> bodies = new ArrayList<>();
    private final List<Headers> headers = new ArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private String retryAfter;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(exchange.getRequestBody().readAllBytes());
            headers.add(exchange.getRequestHeaders());
            if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private WebhookSink open(Map<String, Object> config, ConnectionConfig connection) {
        WebhookSink sink = new WebhookSink(connection);
        sink.init(new SinkContext("job-1", "webhook:hooks:users", config), SCHEMA);
        return sink;
    }

    private static ChangeBatch batch(Change... changes) {
        return new ChangeBatch("job-1", "webhook:hooks:users", 7, SCHEMA, List.of(changes));
    }

    private static Change upsert(long id, String name) {
        return new Change(Change.Op.UPSERT, Map.of("id", id), Map.of("id", id, "name", name), "h", "k" + id, Instant.EPOCH, id);
    }

    private static Change delete(long id, Map<String, Object> lastRow) {
        return new Change(Change.Op.DELETE, Map.of("id", id), lastRow, null, "d" + id, Instant.EPOCH, id);
    }

    @Test
    void postsTheEnvelopeSignedWithHmac() throws Exception {
        ConnectionConfig conn = new ConnectionConfig("hooks", "webhook", Map.of("url", url(), "authType", "hmac", "password", "s3cret"));
        try (WebhookSink sink = open(Map.of("jobName", "users-sync"), conn)) {
            var result = sink.apply(batch(upsert(1, "Ann"), delete(2, null)));
            assertThat(result.upserts()).isEqualTo(1);
            assertThat(result.deletes()).isEqualTo(1);
        }
        JsonNode body = JSON.readTree(bodies.getFirst());
        assertThat(body.get("job").asText()).isEqualTo("users-sync");
        assertThat(body.get("batchId").asLong()).isEqualTo(7);
        assertThat(body.get("object").asText()).isEqualTo("app.users");
        assertThat(body.at("/changes/0/op").asText()).isEqualTo("upsert");
        assertThat(body.at("/changes/0/row/name").asText()).isEqualTo("Ann");
        assertThat(body.at("/changes/1/op").asText()).isEqualTo("delete");
        assertThat(body.at("/changes/1/key/id").asLong()).isEqualTo(2);
        assertThat(body.at("/changes/1").has("row")).isFalse();

        Headers h = headers.getFirst();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(h.getFirst("X-DataSync-Signature")).isEqualTo("sha256=" + HexFormat.of().formatHex(mac.doFinal(bodies.getFirst())));
        assertThat(h.getFirst("X-DataSync-Job")).isEqualTo("users-sync");
        assertThat(h.getFirst("Idempotency-Key")).hasSize(64);
    }

    @Test
    void bearerAuthAndExtraHeadersFromInlineConfig() {
        try (WebhookSink sink = open(Map.of("url", url(), "auth", Map.of("type", "bearer", "token", "t0k"),
            "headers", Map.of("X-Team", "data")), null)) {
            sink.apply(batch(upsert(1, "Ann")));
        }
        assertThat(headers.getFirst().getFirst("Authorization")).isEqualTo("Bearer t0k");
        assertThat(headers.getFirst().getFirst("X-Team")).isEqualTo("data");
        assertThat(headers.getFirst().getFirst("Idempotency-Key")).isEqualTo("k1");
    }

    @Test
    void serverErrorsAreRetryableWithRetryAfter() {
        status.set(503);
        retryAfter = "30";
        try (WebhookSink sink = open(Map.of("url", url()), null)) {
            assertThatThrownBy(() -> sink.apply(batch(upsert(1, "Ann"))))
                .isInstanceOfSatisfying(SinkException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(30));
                });
        }
    }

    @Test
    void clientErrorsArePermanent() {
        status.set(400);
        try (WebhookSink sink = open(Map.of("url", url()), null)) {
            assertThatThrownBy(() -> sink.apply(batch(upsert(1, "Ann"))))
                .isInstanceOfSatisfying(SinkException.class, e -> assertThat(e.retryable()).isFalse())
                .hasMessageContaining("HTTP 400");
        }
    }

    @Test
    void anUnreachableConsumerIsRetryable() {
        String closed = url();
        server.stop(0);
        try (WebhookSink sink = open(Map.of("url", closed), null)) {
            assertThatThrownBy(() -> sink.apply(batch(upsert(1, "Ann"))))
                .isInstanceOfSatisfying(SinkException.class, e -> assertThat(e.retryable()).isTrue());
            assertThat(sink.health().up()).isFalse();
        }
    }

    @Test
    void metadataAddressesAreRefused() {
        try (WebhookSink sink = open(Map.of("url", "http://169.254.169.254/latest/meta-data"), null)) {
            assertThatThrownBy(() -> sink.apply(batch(upsert(1, "Ann"))))
                .isInstanceOfSatisfying(SinkException.class, e -> assertThat(e.retryable()).isFalse())
                .hasMessageContaining("link-local");
        }
    }

    @Test
    void privateNetworksAreRefusedWhenConfigured() {
        System.setProperty("datasync.webhook.block-private-networks", "true");
        try (WebhookSink sink = open(Map.of("url", url()), null)) {
            assertThatThrownBy(() -> sink.apply(batch(upsert(1, "Ann")))).hasMessageContaining("private networks");
        } finally {
            System.clearProperty("datasync.webhook.block-private-networks");
        }
    }

    @Test
    void deletesAsFlaggedUpsertsAndPerRowBatches() throws Exception {
        try (WebhookSink sink = open(Map.of("url", url(), "deleteAs", "upsert-with-flag", "batch", Map.of("mode", "per-row")), null)) {
            assertThat(sink.capabilities().batchLimit()).isEqualTo(1);
            assertThat(sink.capabilities().needsDeletedRows()).isTrue();
            sink.apply(batch(delete(2, Map.of("id", 2L, "name", "Bob"))));
        }
        JsonNode change = JSON.readTree(bodies.getFirst()).at("/changes/0");
        assertThat(change.get("op").asText()).isEqualTo("upsert");
        assertThat(change.at("/row/name").asText()).isEqualTo("Bob");
        assertThat(change.at("/row/_is_deleted").asBoolean()).isTrue();
    }

    @Test
    void healthCountsAnyAnswer() {
        status.set(405);
        try (WebhookSink sink = open(Map.of("url", url()), null)) {
            assertThat(sink.health().up()).isTrue();
        }
    }

    @Test
    void readsRetryAfterAsSecondsOrDate() {
        assertThat(WebhookSink.retryAfter("5")).isEqualTo(Duration.ofSeconds(5));
        assertThat(WebhookSink.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).isEqualTo(Duration.ZERO);
        assertThat(WebhookSink.retryAfter("soon")).isNull();
    }
}
