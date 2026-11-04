package io.datasync.cli;

import com.sun.net.httpserver.HttpServer;
import io.datasync.core.DeliveryResult;
import io.datasync.core.JobRunner;
import io.datasync.core.Outbox;
import io.datasync.core.RunKind;
import io.datasync.core.RunResult;
import io.datasync.core.StateStore;
import io.datasync.core.config.ConfigLoader;
import io.datasync.core.config.DataSyncConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sizing check at the expected production scale: 50,000 rows of about 400 bytes, a database sink and a webhook that is
 * down at first. Prints time and peak heap per step. Opt-in, since it takes a few minutes:
 * {@code ./mvnw -pl datasync-cli test -Dtest=LoadTest -Dload=true -DargLine=-Xmx256m}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "load", matches = "true")
class LoadTest {

    static final int ROWS = 50_000;

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17");

    @TempDir
    Path dir;

    @Test
    void fiftyThousandRowsToADatabaseAndAWebhook() throws Exception {
        sql("CREATE SCHEMA src",
            "CREATE TABLE src.orders (id bigint PRIMARY KEY, customer varchar(100), email varchar(200), status varchar(20),"
                + " amount numeric(12,2), qty int, created_at timestamp, updated_at timestamp, note text, active boolean)",
            "INSERT INTO src.orders SELECT g, 'Customer ' || g, 'customer' || g || '@example.com', 'NEW', g % 1000 + 0.99, g % 7,"
                + " now() - g * interval '1 minute', now(), repeat('note ', 40), g % 2 = 0 FROM generate_series(1, " + ROWS + ") g");

        AtomicBoolean up = new AtomicBoolean(false);
        AtomicInteger requests = new AtomicInteger();
        HttpServer hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (up.get()) requests.incrementAndGet();
            exchange.sendResponseHeaders(up.get() ? 204 : 503, -1);
            exchange.close();
        });
        hook.start();
        Path state = dir.resolve("state.db");
        DataSyncConfig config = ConfigLoader.load(new StringReader("""
            connections:
              - { name: pg, kind: jdbc-postgres, url: "%s", username: %s, password: %s }
            jobs:
              - name: orders
                source: { connection: pg, object: src.orders }
                sinks:
                  - { type: jdbc, connection: pg, target: public.orders_copy }
                  - type: webhook
                    url: http://127.0.0.1:%d/hook
                    retry: { maxAttempts: 1 }
            """.formatted(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), hook.getAddress().getPort())), System::getenv);

        System.out.printf("%nLoad test: %,d rows, max heap %d MB%n", ROWS, Runtime.getRuntime().maxMemory() >> 20);
        System.out.printf("%-46s %9s %10s %12s%n", "step", "seconds", "peak MB", "state.db MB");
        try (StateStore store = new StateStore(state)) {
            JobRunner runner = new JobRunner(config, store);
            Outbox outbox = new Outbox(store);
            String webhook = "webhook";   // an inline webhook has no connection or target in its id

            RunResult first = step("first run: read and queue every row", state, () -> runner.run("orders", RunKind.POLL, false));
            assertThat(first.stats().upserts()).isEqualTo(ROWS);
            step("deliver: database, webhook down -> paused", state, () -> runner.deliverAll("orders", true));
            assertThat(count("SELECT count(*) FROM public.orders_copy")).isEqualTo(ROWS);
            System.out.printf("  webhook backlog: %d batches%n", outbox.backlog("orders", webhook));

            RunResult idle = step("second run: nothing changed", state, () -> runner.run("orders", RunKind.POLL, false));
            assertThat(idle.stats().unchanged()).isEqualTo(ROWS);
            step("deliver: nothing to send", state, () -> runner.deliverAll("orders", true));

            sql("UPDATE src.orders SET status = 'SHIPPED', updated_at = now() WHERE id % 100 = 0",
                "DELETE FROM src.orders WHERE id % 1000 = 1",
                "INSERT INTO src.orders SELECT g, 'New ' || g, 'n@example.com', 'NEW', 1, 1, now(), now(), 'x', true"
                    + " FROM generate_series(" + (ROWS + 1) + ", " + (ROWS + 50) + ") g");
            RunResult changes = step("third run: 500 updates, 50 deletes, 50 inserts", state, () -> runner.run("orders", RunKind.POLL, false));
            assertThat(changes.stats().upserts()).isEqualTo(550);
            assertThat(changes.stats().deletes()).isEqualTo(50);
            step("deliver: the changes", state, () -> runner.deliverAll("orders", true));

            up.set(true);
            outbox.resume("orders", webhook);
            List<DeliveryResult> caughtUp = step("webhook back, resumed: deliver its backlog", state, () -> runner.deliverAll("orders", true));
            assertThat(caughtUp).allMatch(DeliveryResult::caughtUp);
            System.out.printf("  webhook requests: %d (up to 500 changes each)%n", requests.get());
        } finally {
            hook.stop(0);
        }
    }

    private static <T> T step(String name, Path state, Callable<T> work) throws Exception {
        System.gc();
        List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).toList();
        heap.forEach(MemoryPoolMXBean::resetPeakUsage);
        long start = System.nanoTime();
        T result = work.call();
        double seconds = (System.nanoTime() - start) / 1e9;
        long peak = heap.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
        long size = 0;
        for (String f : new String[] {"state.db", "state.db-wal"}) {
            Path p = state.resolveSibling(f);
            if (Files.exists(p)) size += Files.size(p);
        }
        System.out.printf("%-46s %9.1f %10d %12.1f%n", name, seconds, peak >> 20, size / 1048576.0);
        return result;
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void sql(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String s : statements) st.execute(s);
        }
    }
}
