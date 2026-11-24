package io.datasync.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.datasync.api.ConnectionConfig;
import io.datasync.api.Sink;
import io.datasync.api.SinkProvider;
import io.datasync.api.SourceConnector;
import io.datasync.api.SourceProvider;

/** ServiceLoader entry points for "jdbc-&lt;vendor&gt;" connections and the "jdbc" sink type. */
public final class JdbcProviders {

    private JdbcProviders() {}

    public static final class Sources implements SourceProvider {
        @Override
        public boolean supports(String connectionKind) {
            return Dialects.forKind(connectionKind).isPresent();
        }

        @Override
        public SourceConnector open(ConnectionConfig connection) {
            return new JdbcSource(connection.kind(), dialect(connection), pool(connection, true));
        }
    }

    public static final class Sinks implements SinkProvider {
        @Override
        public boolean supports(String sinkType) {
            return "jdbc".equals(sinkType);
        }

        @Override
        public Sink open(ConnectionConfig connection) {
            if (connection == null) throw new IllegalArgumentException("jdbc sink requires a connection");
            return new JdbcSink(connection.kind(), dialect(connection), pool(connection, false));
        }
    }

    private static SqlDialect dialect(ConnectionConfig c) {
        return Dialects.forKind(c.kind())
            .orElseThrow(() -> new IllegalArgumentException("no SQL dialect for connection kind '" + c.kind() + "'"));
    }

    /** Bounded per-connection pool. Sink connections are transactional (auto-commit off). */
    private static HikariDataSource pool(ConnectionConfig c, boolean readOnly) {
        HikariConfig h = new HikariConfig();
        h.setPoolName(c.name());
        String url = c.require("url");
        h.setJdbcUrl(url);
        if (url.startsWith("jdbc:oracle:")) {
            // The driver sends the JVM zone as a region name (e.g. Asia/Jakarta); older servers such as 11g may not
            // know it and fail logon with ORA-01882. Sending an offset (+07:00) works on every version.
            h.addDataSourceProperty("oracle.jdbc.timezoneAsRegion",
                c.props().getOrDefault("oracle.jdbc.timezoneAsRegion", "false"));
        }
        h.setUsername(c.props().get("username"));
        h.setPassword(c.props().get("password"));
        h.setMaximumPoolSize(Integer.parseInt(c.props().getOrDefault("maxConcurrency", "2")));
        h.setReadOnly(readOnly);
        h.setAutoCommit(readOnly);
        return new HikariDataSource(h);
    }
}
