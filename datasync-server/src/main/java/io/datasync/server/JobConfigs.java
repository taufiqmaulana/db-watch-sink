package io.datasync.server;

import io.datasync.api.ConnectionConfig;
import io.datasync.core.config.DataSyncConfig;
import io.datasync.core.config.DataSyncConfig.JobDef;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns stored jobs and connections into what the engine runs. Jobs reference connections by id,
 * so the engine sees each connection under its id, with the password decrypted.
 */
@Singleton
public class JobConfigs {

    @Inject
    Db db;

    @Inject
    Secrets secrets;

    public JobDef job(String jobId) {
        return db.one("SELECT config_json FROM job WHERE id = ?", rs -> Json.read(rs.getString(1), JobDef.class), jobId)
            .orElseThrow(() -> new NotFoundException("Job not found"));
    }

    /** The engine config for {@code def}, which runs under the name {@code jobId}. */
    public DataSyncConfig engineConfig(String jobId, JobDef def) {
        List<Map<String, Object>> connections = connectionIds(def).stream().map(this::connectionProps).toList();
        return new DataSyncConfig(connections, List.of(def.withName(jobId)));
    }

    public ConnectionConfig connection(String id) {
        Map<String, String> props = new LinkedHashMap<>();
        connectionProps(id).forEach((k, v) -> props.put(k, v.toString()));
        return new ConnectionConfig(id, props.get("kind"), props);
    }

    public static Set<String> connectionIds(JobDef def) {
        Set<String> ids = new LinkedHashSet<>();
        if (def.source() != null && def.source().connection() != null) ids.add(def.source().connection());
        def.sinks().stream().map(s -> s.get("connection")).filter(Objects::nonNull).forEach(c -> ids.add(c.toString()));
        return ids;
    }

    private Map<String, Object> connectionProps(String id) {
        return db.one("SELECT kind, config_json, secret_enc, max_concurrency FROM connection WHERE id = ?", rs -> {
            Map<String, Object> props = new LinkedHashMap<>(Json.map(rs.getString("config_json")));
            props.put("name", id);
            props.put("kind", rs.getString("kind"));
            props.put("maxConcurrency", rs.getInt("max_concurrency"));
            byte[] secret = rs.getBytes("secret_enc");
            if (secret != null) props.put("password", secrets.decrypt(secret));
            return props;
        }, id).orElseThrow(() -> new IllegalArgumentException("Connection " + id + " does not exist"));
    }
}
