package io.datasync.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.datasync.core.config.ConfigLoader;
import io.datasync.core.config.DataSyncConfig;
import io.datasync.core.config.DataSyncConfig.JobDef;
import io.datasync.core.config.DataSyncConfig.SourceDef;
import io.datasync.server.Audit;
import io.datasync.server.Db;
import io.datasync.server.Json;
import io.datasync.server.Scheduler;
import io.datasync.server.Secrets;
import io.datasync.server.api.ConnectionResource.ConnectionInput;
import io.datasync.server.api.ConnectionResource.ConnectionRole;
import io.datasync.server.api.JobResource.JobInput;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.jboss.resteasy.reactive.RestQuery;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration as code (design 13): connections and jobs as YAML in the CLI's format, so it can live in Git. Exported
 * passwords are {@code ${env:NAME}} references; on import, an unset reference keeps the stored password. Import matches
 * connections and jobs by name, creates or updates them, and leaves others alone. It is all or nothing, and
 * {@code dryRun} reports what would change without changing anything.
 */
@Path("/")
public class ConfigResource {

    public enum Action { CREATE, UPDATE, UNCHANGED }

    public enum Kind { CONNECTION, JOB }

    public record ImportItem(Kind kind, String name, Action action) {}

    public record ImportResult(boolean dryRun, List<ImportItem> items) {}

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).enable(YAMLGenerator.Feature.MINIMIZE_QUOTES).build())
        .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    /** Stands in for an unset ${env:NAME} while parsing, so a password can fall back to the stored one. */
    private static final String UNSET = "\u0000unset:";

    @Inject
    Db db;

    @Inject
    Secrets secrets;

    @Inject
    ConnectionResource connections;

    @Inject
    JobResource jobs;

    @Inject
    Scheduler scheduler;

    @Inject
    Audit audit;

    @GET
    @Path("/export")
    @Produces("application/yaml")
    public String export() {
        Map<String, String> names = new LinkedHashMap<>();
        List<Map<String, Object>> conns = db.query("SELECT id, name, kind, role, config_json, secret_enc IS NOT NULL AS has_secret,"
            + " max_concurrency FROM connection ORDER BY name", rs -> {
            names.put(rs.getString("id"), rs.getString("name"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", rs.getString("name"));
            c.put("kind", rs.getString("kind"));
            c.put("role", rs.getString("role"));
            c.putAll(Json.map(rs.getString("config_json")));
            if (rs.getBoolean("has_secret")) c.put("password", "${env:" + envName(rs.getString("name")) + "_PASSWORD}");
            if (!"webhook".equals(rs.getString("kind"))) c.put("maxConcurrency", rs.getInt("max_concurrency"));
            return c;
        });
        List<JobDef> defs = db.query("SELECT config_json FROM job ORDER BY name", rs -> Json.read(rs.getString(1), JobDef.class))
            .stream().map(def -> withConnections(def, id -> names.getOrDefault(id, id))).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connections", conns);
        out.put("jobs", defs);
        try {
            return "# DataSync configuration. Set the ${env:...} variables before importing it or running it with the CLI.\n"
                + YAML.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @POST
    @Path("/import")
    @Consumes({"application/yaml", "text/yaml", "text/plain"})
    public ImportResult importConfig(String yaml, @RestQuery boolean dryRun) {
        DataSyncConfig config;
        try {
            config = ConfigLoader.load(new StringReader(Objects.requireNonNullElse(yaml, "")), name -> Optional.ofNullable(System.getenv(name)).orElse(UNSET + name));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Not a valid configuration file: " + e.getMessage());
        }
        if (config == null) throw new IllegalArgumentException("The file is empty");
        List<ImportItem> items = new ArrayList<>();
        List<String> touchedJobs = new ArrayList<>();
        try {
            db.tx(c -> {
                for (Map<String, Object> conn : config.connections()) items.add(importConnection(conn));
                Map<String, String> ids = new LinkedHashMap<>();
                db.query("SELECT id, name FROM connection", rs -> ids.put(rs.getString(2), rs.getString(1)));
                for (JobDef def : config.jobs()) items.add(importJob(def, ids, touchedJobs));
                if (dryRun) throw new DryRun();
                return null;
            });
        } catch (DryRun rolledBack) {
            // nothing was kept
        } finally {
            touchedJobs.forEach(scheduler::reschedule);
        }
        if (!dryRun) {
            audit.record("config.import", "config", Map.of("changed", items.stream().filter(i -> i.action() != Action.UNCHANGED)
                .map(i -> i.kind().name().toLowerCase(Locale.ROOT) + " " + i.name()).toList()));
        }
        return new ImportResult(dryRun, items);
    }

    private ImportItem importConnection(Map<String, Object> c) {
        String name = text(c.get("name"));
        String context = "Connection " + Objects.requireNonNullElse(name, "without a name") + ": ";
        try {
            String kind = text(c.get("kind"));
            String role = text(c.get("role"));
            String password = text(c.get("password"));
            var existing = db.one("SELECT id, kind, role, config_json, secret_enc, max_concurrency FROM connection WHERE name = ?",
                rs -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getInt(6)}, name);
            if (password != null && password.contains(UNSET)) {
                if (existing.isEmpty()) throw new IllegalArgumentException("set " + unsetName(password) + " for the password, or write it in the file");
                password = null;
            }
            for (Map.Entry<String, Object> field : c.entrySet()) {
                if (!field.getKey().equals("password") && field.getValue() instanceof String s && s.contains(UNSET)) throw new IllegalArgumentException("environment variable " + unsetName(s) + " is not set");
            }
            ConnectionInput in = new ConnectionInput(name, kind,
                role == null ? ("webhook".equals(kind) ? ConnectionRole.SINK : ConnectionRole.BOTH) : ConnectionRole.valueOf(role.toUpperCase(Locale.ROOT)),
                text(c.get("url")), text(c.get("username")), password, text(c.get("authType")),
                c.get("maxConcurrency") == null ? null : Integer.valueOf(c.get("maxConcurrency").toString()));
            if (existing.isEmpty()) {
                connections.create(in);
                return new ImportItem(Kind.CONNECTION, name, Action.CREATE);
            }
            Object[] e = existing.get();
            boolean same = Objects.equals(e[1], in.kind()) && Objects.equals(e[2], in.role().name())
                && Json.map((String) e[3]).equals(Json.map(ConnectionResource.configJson(in)))
                && (int) e[5] == Objects.requireNonNullElse(in.maxConcurrency(), 2)
                && (in.password() == null || in.password().isEmpty() || (e[4] != null && in.password().equals(secrets.decrypt((byte[]) e[4]))));
            if (same) return new ImportItem(Kind.CONNECTION, name, Action.UNCHANGED);
            connections.update((String) e[0], in);
            return new ImportItem(Kind.CONNECTION, name, Action.UPDATE);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(context + e.getMessage(), e);
        }
    }

    private ImportItem importJob(JobDef raw, Map<String, String> connectionIds, List<String> touched) {
        String context = "Job " + Objects.requireNonNullElse(raw.name(), "without a name") + ": ";
        try {
            if (Json.write(raw).contains(UNSET)) throw new IllegalArgumentException("an environment variable it uses is not set");
            JobDef def = withConnections(raw, name -> {
                String id = connectionIds.get(name);
                if (id == null) throw new IllegalArgumentException("unknown connection " + name);
                return id;
            });
            var existing = db.one("SELECT id, version, config_json FROM job WHERE name = ?",
                rs -> new Object[] {rs.getString(1), rs.getInt(2), rs.getString(3)}, def.name());
            if (existing.isEmpty()) {
                String id = jobs.create(new JobInput(def, null)).getEntity().id();
                touched.add(id);
                return new ImportItem(Kind.JOB, def.name(), Action.CREATE);
            }
            Object[] e = existing.get();
            String id = (String) e[0];
            if (Json.write(jobs.validate(def, id)).equals(e[2])) return new ImportItem(Kind.JOB, def.name(), Action.UNCHANGED);
            jobs.update(id, new JobInput(def, (Integer) e[1]));
            touched.add(id);
            return new ImportItem(Kind.JOB, def.name(), Action.UPDATE);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(context + e.getMessage(), e);
        }
    }

    /** The job with its source and sink connections mapped, between ids (stored) and names (YAML). */
    private static JobDef withConnections(JobDef def, java.util.function.UnaryOperator<String> map) {
        List<Map<String, Object>> sinks = def.sinks().stream().map(s -> {
            Map<String, Object> sink = new LinkedHashMap<>(s);
            if (sink.get("connection") != null) sink.put("connection", map.apply(sink.get("connection").toString()));
            return sink;
        }).toList();
        SourceDef source = def.source() == null || def.source().connection() == null ? def.source()
            : new SourceDef(map.apply(def.source().connection()), def.source().object());
        return new JobDef(def.name(), source, def.keys(), def.columns(), def.mode(), def.batchSize(), def.fetchSize(), def.maxDeletePercent(),
            def.queryTimeoutSeconds(), sinks, def.schedule(), def.every(), def.fixedDelay(), def.jitterPercent(), def.watermark(), def.reconcile(), def.outbox());
    }

    /** ORACLE_PROD for "oracle-prod". */
    static String envName(String connectionName) {
        return connectionName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String unsetName(String value) {
        int at = value.indexOf(UNSET) + UNSET.length();
        int end = at;
        while (end < value.length() && (Character.isLetterOrDigit(value.charAt(end)) || value.charAt(end) == '_')) end++;
        return value.substring(at, end);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class DryRun extends RuntimeException {
        DryRun() {
            super(null, null, false, false);
        }
    }
}
