package io.datasync.dialect.postgres;

import io.datasync.api.ObjectRef;
import io.datasync.jdbc.BulkLoader;
import io.datasync.jdbc.SqlDialect;
import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Stages rows in a temp table dropped at commit, loaded with {@code COPY ... FROM STDIN} in text format. */
final class PgCopyLoader implements BulkLoader {

    private final SqlDialect dialect;
    private final AtomicLong seq = new AtomicLong();

    PgCopyLoader(SqlDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public String stage(Connection c, ObjectRef target, List<String> columns, List<List<Object>> rows) throws SQLException {
        String staging = dialect.quote("datasync_stg_" + seq.incrementAndGet());
        String cols = columns.stream().map(dialect::quote).collect(Collectors.joining(", "));
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TEMP TABLE " + staging + " ON COMMIT DROP AS SELECT " + cols
                + " FROM " + dialect.qualify(target) + " WITH NO DATA");
        }
        StringBuilder text = new StringBuilder();
        for (List<Object> row : rows) {
            text.append(row.stream().map(PgCopyLoader::copyValue).collect(Collectors.joining("\t"))).append('\n');
        }
        try {
            c.unwrap(PGConnection.class).getCopyAPI()
                .copyIn("COPY " + staging + " (" + cols + ") FROM STDIN", new StringReader(text.toString()));
        } catch (IOException e) {
            throw new SQLException("COPY into " + staging + " failed", e);
        }
        return staging;
    }

    static String copyValue(Object v) {
        if (v == null) return "\\N";
        String s = switch (v) {
            case Boolean b -> b ? "t" : "f";
            case byte[] b -> "\\x" + HexFormat.of().formatHex(b);
            case BigDecimal d -> d.toPlainString();
            default -> v.toString();
        };
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
