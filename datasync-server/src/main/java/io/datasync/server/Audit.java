package io.datasync.server;

import io.datasync.server.auth.CurrentUser;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Records configuration changes and operator actions. Entities are named like {@code job:<id>}. */
@Singleton
public class Audit {

    public record Entry(long id, Instant at, String actor, String action, String entity, Map<String, Object> detail) {}

    @Inject
    Db db;

    @Inject
    CurrentUser currentUser;

    public void record(String action, String entity, Map<String, Object> detail) {
        record(currentUser.username(), action, entity, detail);
    }

    public void record(String actor, String action, String entity, Map<String, Object> detail) {
        db.update("INSERT INTO audit_log (at, actor, action, entity, detail_json) VALUES (?, ?, ?, ?, ?)",
            Instant.now(), actor, action, entity, Json.write(detail));
    }

    /** Newest first; {@code entity} is optional. */
    public List<Entry> list(String entity, int limit) {
        String where = entity == null ? "" : " WHERE entity = ?";
        Object[] args = entity == null ? new Object[] {limit} : new Object[] {entity, limit};
        return db.query("SELECT id, at, actor, action, entity, detail_json FROM audit_log" + where + " ORDER BY id DESC LIMIT ?",
            rs -> new Entry(rs.getLong("id"), Db.instant(rs, "at"), rs.getString("actor"), rs.getString("action"),
                rs.getString("entity"), Json.map(rs.getString("detail_json"))),
            args);
    }
}
