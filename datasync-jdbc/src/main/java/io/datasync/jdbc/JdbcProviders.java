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
        h.setJdbcUrl(c.require("url"));
        h.setUsername(c.props().get("username"));
        h.setPassword(c.props().get("password"));
        h.setMaximumPoolSize(Integer.parseInt(c.props().getOrDefault("maxConcurrency", "2")));
        h.setReadOnly(readOnly);
        h.setAutoCommit(readOnly);
        return new HikariDataSource(h);
    }
}
