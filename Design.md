# DataSync: Polling-Based Table/View Sync Service

**Status:** Draft v0.5 (implementation review applied, ready for M1, see section 18)
**Stack:** Java 21 (Quarkus), React (Vite + TypeScript), embedded SQLite state store
**Starting vendors:** Oracle, PostgreSQL (both directions)

---

## 1. Goals and non-goals

### Goals
1. **Small footprint.** One process, one container, no mandatory external services. Target: under 256 MB RSS in JVM mode, under 100 MB if built as a native image.
2. **GUI management and monitoring.** Create connections and jobs, run, pause, inspect, retry, all from a React UI.
3. **Multiple sources and multiple sinks.** A job reads one source object (table or view) and delivers to one or more sinks.
4. **No vendor lock-in.** All vendor-specific code lives behind small SPIs. Oracle and PostgreSQL are the first implementations, not special cases.
5. **Custom sinks.** Webhook first, then event brokers (Kafka, NATS, RabbitMQ, SNS/SQS) and external plugins.
6. **Minimal source configuration.** Only a read-only account with `SELECT` is needed. No triggers, no LogMiner, no supplemental logging, no replication slots.
7. **Fixed-interval polling.** Real-time is explicitly out of scope. Use Debezium if that is ever needed.

### Non-goals
- Sub-second latency or strict ordering across tables.
- Multi-table transactional consistency.
- Arbitrary transformations (only column select, rename and simple type coercion in v1).
- Schema migration management on the target beyond optional auto-create and add-nullable-column.

### Delivery guarantee
**At-least-once**, with deterministic idempotency keys. DB sinks are idempotent through upsert and delete-by-key. Event sinks must dedupe using the `Idempotency-Key` header or message key.

---

## 2. High-level architecture

```
 Browser (React SPA)
        │  REST + SSE
        ▼
┌──────────────────────────── DataSync (single JVM) ─────────────────────────────┐
│  API layer (JAX-RS)      Auth (local/OIDC)       Static UI (served from jar)    │
│                                                                                 │
│  Scheduler ──▶ JobRunner (virtual threads)                                      │
│                    │                                                            │
│        ┌───────────┴────────────┐                                               │
│        ▼                        ▼                                               │
│  SourceConnector SPI      ChangeDetector                                        │
│  (Oracle, PG, …)          (watermark | snapshot-diff | hybrid)                  │
│        │                        │                                               │
│        └──────▶ canonical Change batches ──▶ Outbox (per-sink offsets)          │
│                                                  │        │         │           │
│                                              DB sinks  Webhook   Broker sinks   │
│                                                                                 │
│  State store (SQLite / optional PG): config, cursors, hash index, outbox,       │
│  run history, dead letters, audit                                               │
│                                                                                 │
│  Metrics (Micrometer → /metrics)   Health (/q/health)   Logs (JSON)             │
└─────────────────────────────────────────────────────────────────────────────────┘
        ▲ JDBC (read-only)                                  │ JDBC / HTTP / broker
   Source DBs                                             Sink targets
```

### Key design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Framework | **Quarkus** | Fast startup, low memory, optional GraalVM native image, good REST and Micrometer support |
| Concurrency | **Java 21 virtual threads** | Many I/O-bound jobs without a large thread pool or reactive complexity |
| Source access | **Plain JDBC** with a bounded **HikariCP** pool per connection, created at runtime | Widest vendor coverage, no vendor-specific infrastructure. Quarkus Agroal datasources are fixed at build time, so they are used only for the state store |
| Scheduling | **Own scheduler** (`cron-utils` + `ScheduledExecutorService`, runs on virtual threads) | Jobs are created and changed at runtime from the GUI, which the Quarkus scheduler does not support |
| Oracle driver | `ojdbc11` thin | Pure Java, no Instant Client install |
| PostgreSQL driver | `pgjdbc` (`CopyManager` for bulk) | Fast `COPY`-based loading |
| State store | **SQLite** (default), PostgreSQL (optional, for HA/multi-node later) | No extra service, single file, easy backup |
| Migrations | Flyway | Same migrations for SQLite and PG state stores |
| UI | React + Vite + TypeScript, built into `src/main/resources/META-INF/resources` | One artifact to deploy |
| Extensibility | Java `ServiceLoader` SPIs, drivers and plugins loaded from a `plugins/` directory | Add vendors or sinks without rebuilding the core |

> **Decision:** JVM mode only (a footprint of about 200 MB is acceptable). This removes the native-image constraints, so SQLite JDBC, dynamic driver loading and `ServiceLoader` plugins can be used freely. Native image is dropped from the roadmap.

---

## 3. Module layout

```
datasync/
├─ datasync-api/            # SPI interfaces + canonical model (no dependencies)
├─ datasync-core/           # engine: scheduler, runner, detectors, outbox, state repos
├─ datasync-source-jdbc/    # generic JDBC source + dialect SPI
├─ datasync-dialect-oracle/
├─ datasync-dialect-postgres/
├─ datasync-sink-jdbc/      # generic JDBC sink + dialect SPI (bulk load, upsert)
├─ datasync-sink-webhook/
├─ datasync-sink-kafka/     # later
├─ datasync-server/         # Quarkus app: REST, auth, metrics, static UI
├─ datasync-ui/             # React app (Vite); built output copied into server
└─ docs/
```

`datasync-api` is the only module plugin authors depend on.

---

## 4. Core SPIs and canonical model

### 4.1 Canonical types

Every connector maps native types to and from this set. This is what keeps the engine vendor-neutral.

| Canonical | Notes |
|---|---|
| `BOOLEAN` | Oracle `NUMBER(1)` mapping configurable |
| `INT32`, `INT64` | |
| `DECIMAL(p,s)` | Oracle `NUMBER` with no precision maps to unbounded `DECIMAL` |
| `FLOAT64` | |
| `STRING` | Includes `VARCHAR2`, `CHAR`, `CLOB` (streamed) |
| `DATE` | Date only |
| `TIMESTAMP` / `TIMESTAMP_TZ` | Oracle `DATE` maps to `TIMESTAMP(0)` |
| `BYTES` | `BLOB`, `RAW`, `BYTEA`; excluded by default |
| `JSON` | |
| `UUID` | |

Canonical serialization for hashing (see 5.3) is fixed: UTF-8, decimals normalised (no trailing zeros), timestamps as UTC ISO-8601 with microsecond precision, `NULL` distinct from empty string.

### 4.2 Interfaces

```java
public interface SourceConnector extends AutoCloseable {
    String type();                                    // "jdbc-oracle", "jdbc-postgres", ...
    ObjectSchema describe(ObjectRef ref);             // columns, types, PK candidates
    List<ObjectRef> listObjects(String schemaFilter); // for the GUI picker
    RecordCursor read(ReadSpec spec);                 // watermark or keyset scan
    Set<String> primaryKeyOf(ObjectRef ref);
}

public record ReadSpec(
    ObjectRef object,
    List<String> columns,
    List<String> keyColumns,
    Optional<WatermarkSpec> watermark,   // column + lower bound (already overlap-adjusted)
    Optional<KeysetPosition> after,      // resume position for chunked scan
    int fetchSize,
    int chunkSize,
    Duration queryTimeout) {}

public interface Sink extends AutoCloseable {
    String type();                       // "jdbc-postgres", "webhook", "kafka", ...
    void init(SinkContext ctx, ObjectSchema schema);   // create/verify target
    ApplyResult apply(ChangeBatch batch);              // MUST be idempotent
    SinkCapabilities capabilities();     // deletes, schemaEvolution, ordering, batchLimit
    HealthStatus health();
}

public record Change(
    Op op,                      // UPSERT | DELETE
    Map<String,Object> key,
    Map<String,Object> row,     // null for DELETE
    String rowHash,
    String idempotencyKey,      // sha256(jobId | canonicalKey | rowHash-or-"del")
    Instant observedAt,
    long seq) {}

public record ChangeBatch(String jobId, String sinkId, long batchId,
                          ObjectSchema schema, List<Change> changes) {}
```

### 4.3 Dialect SPI (for JDBC-based source and sink)

```java
public interface SqlDialect {
    String vendor();                                   // "oracle", "postgres"
    String quote(String identifier);
    String normalizeIdentifier(String id);             // Oracle UPPER vs PG lower policy
    String keysetQuery(ReadSpec spec);                 // FETCH FIRST n ROWS vs LIMIT n
    CanonicalType toCanonical(JdbcColumn col);
    String nativeType(CanonicalType t);                // for auto-create on sink
    String upsertSql(TableRef t, List<String> cols, List<String> keys);  // MERGE vs ON CONFLICT
    BulkLoader bulkLoader();                           // COPY vs array-bound batch insert
    String currentTimeSql();                           // SYSTIMESTAMP vs now()
}
```

Adding SQL Server or MySQL means adding one dialect module. The engine, GUI and state store are untouched.

### 4.4 Column transformations (nice to have, post-v1)

Not in v1, but the design leaves a hook so it can be added without reworking the pipeline.

- A job's column mapping already allows `include`, `exclude`, `rename` and type coercion. A mapping entry gets an optional `transform` field.
- Transforms run in the engine **after** the source read and **before** hashing and delivery, so every sink sees the same transformed row and the hash index reflects the transformed values.
- Built-in transforms to start with: `mask` (full, partial, last-N), `hash` (SHA-256 with salt), `constant`, `nullIf`, `trim`, `upper`/`lower`, `concat`.
- Expressions: a small sandboxed expression language (CEL or JEXL) for derived columns, with no I/O or reflection access, evaluated per row with a time limit.
- The dry-run preview in the job wizard shows before/after values.

```yaml
columns:
  include: "*"
  map:
    EMAIL:      { transform: { type: mask, keep: 3 } }
    SSN:        { transform: { type: hash, salt: ${env:HASH_SALT} } }
    FULL_NAME:  { expr: "FIRST_NAME + ' ' + LAST_NAME" }
```

---

## 5. Change detection

Only `SELECT` is used on the source. Each job picks a mode.

| Mode | Use when | Detects deletes | Source cost |
|---|---|---|---|
| `WATERMARK` | Reliable `updated_at` or monotonic ID | No | Very low |
| `SNAPSHOT_DIFF` | No watermark (typical for views), small to medium size | Yes | Full scan per run |
| `HYBRID` (recommended) | Large table with watermark | Yes, at reconcile | Low per run, full scan on reconcile cron |

> **Default for the initial scale (about 10K rows per object):** use `SNAPSHOT_DIFF`. A full scan of 10K rows is cheap, needs no `updated_at` column, and detects deletes on every run. Switch a job to `HYBRID` only when an object grows past roughly 100K to 500K rows or the source scan becomes noticeable. The UI suggests the mode from the row count seen on the last run.

### 5.1 Watermark mode
- Query: `WHERE wm >= :lastWatermark - :overlap ORDER BY wm, pk`, chunked by keyset `(wm, pk) > (:lastWm, :lastPk)`. Never use `OFFSET`.
- **Overlap window** (default 5 min) handles late-committing transactions. Re-reads are harmless because delivery is idempotent. Unchanged rows are dropped by the hash index (5.3) before they reach sinks.
- Watermark is evaluated against the **source DB clock** (`currentTimeSql()`), never the app server clock.
- The cursor advances only after all chunk deliveries are durably recorded in the outbox.

### 5.2 Snapshot-diff mode
1. Stream the full source in keyset chunks, computing `keyHash` and `rowHash` for each row.
2. For each chunk, look up the keys in the hash index (one batched `WHERE key_hash IN (…)` query) and emit:
   - key in source, not in index → `UPSERT` (new)
   - key in both, hash differs → `UPSERT` (changed)
   - key in both, hash equal → unchanged (counted, not delivered)
   
   Every key seen gets `last_seen_run = runId`.
3. After the scan completes, keys in the index `WHERE last_seen_run <> runId` were not in the source → `DELETE`.

   A sorted merge-join is deliberately **not** used: the source orders by PK in the vendor's collation, while the index is keyed by `key_hash`, so the two streams are not in the same order.
4. **Delete safety guard:** if deletes exceed `maxDeletePercent` (default 20%) or the scan returned zero rows when the index is non-empty, abort the run, mark it `NEEDS_REVIEW` and alert. This usually means a failed or partial source query.

### 5.3 Hash index
- Table `row_index(job_id, key_hash, key_json, row_hash, last_seen_run, row_json)`.
- `row_json` (compressed last known row) is optional and populated only when a sink of the job uses `deleteAs: "upsert-with-flag"` (8.1), since that option has to send the last known row for a deleted key.
- The index is required by every mode, including `WATERMARK` (it drops unchanged rows re-read in the overlap window), so it is built in M1.
- Row hash is SHA-256 (truncated to 16 bytes) over the canonical serialization of the **selected columns**.
- Approximately 40 to 60 bytes per row. At the current scale (about 10K rows per object) the index is under 1 MB per job. 10 million rows would be about 0.5 GB, still acceptable for SQLite.
- For very large objects, either use `HYBRID` (the index is populated lazily from watermark reads and fully verified at reconcile) or move the state store to PostgreSQL.

### 5.4 Reconcile
A cron-driven snapshot-diff run attached to a `WATERMARK` job. It also corrects any updates missed due to bad watermark data.

### 5.5 Known source pitfalls (documented in UI help)
- Oracle `''` equals `NULL`. Configurable normalisation (`EMPTY_TO_NULL` default for Oracle sources).
- `ORA_ROWSCN` is not used (block-level unless the table has `ROWDEPENDENCIES`).
- Long-running scans can hit `ORA-01555`. Use small chunks, short queries, and a configurable `queryTimeout`.
- Identifier case folding differs. Identifiers are stored as entered and quoted on use. The UI picker returns exact names.

---

## 5A. Scheduling and poll interval

- **Default interval: 5 minutes** (`*/5 * * * *`). Faster intervals are supported per job, so tightening it later is a config change, not a redesign.
- Besides cron, a job accepts a **fixed interval** (`every: 30s`), with a minimum of 10 seconds in v1. Intervals under a minute are expressed this way, since standard cron cannot do them.
- **No overlapping runs:** if a run is still in progress when the next tick fires, the tick is skipped and counted in a `datasync_skipped_ticks_total` metric. The interval is measured from the start of a run, not its end, unless `fixedDelay: true` is set.
- **Jitter** (default up to 10% of the interval) spreads load when many jobs share a source.
- **Guidance for faster polling:** at about 10K rows, a `SNAPSHOT_DIFF` every 30 to 60 seconds is realistic. Below that, or for larger objects, switch the job to `WATERMARK`/`HYBRID` so each poll reads only changed rows. The UI warns when a job's average run time exceeds 50% of its interval.
- The per-connection concurrency cap (section 11) bounds the combined source load from many fast jobs.

---

## 6. Pipeline, outbox and multi-sink delivery

```
read chunk ─▶ detect changes ─▶ write to outbox (state store txn) ─▶ advance cursor
                                           │
              ┌────────────────────────────┼────────────────────────────┐
              ▼                            ▼                            ▼
        sink worker A                sink worker B                sink worker C
        (offset_A)                   (offset_B)                   (offset_C)
```

- **One reader per job**, one **durable outbox** (`outbox_batch` rows), and **one worker per (job, sink)** with its own offset, retry and backoff state.
- A slow or failing sink never blocks other sinks. Backpressure is bounded by `outbox.maxBatches`. If a sink falls beyond retention, it is flagged `RESNAPSHOT_REQUIRED` and the UI offers a one-click resnapshot (full snapshot-diff delivered to that sink only).
- Batches are trimmed once every sink has acked them.
- **Retry policy:** exponential backoff with jitter, max attempts per batch, then the batch moves to **dead letter** (`dead_letter`) and the sink worker is paused or continues based on `onDeadLetter: pause | skip`.
- **Ordering:** per-key ordering is preserved within a sink worker. Cross-key and cross-table ordering are not guaranteed.

### Simplified MVP variant
For the first milestone, a job has exactly one sink and the outbox is bypassed (read → apply → advance cursor in one pass). Introduce the outbox in milestone M6 when fan-out is needed. In this variant the hash index and cursor are updated in one state-store transaction only after the sink has applied the batch. The `Sink.apply` contract is the same either way.

---

## 7. State store schema (SQLite / PostgreSQL)

```sql
CREATE TABLE connection (
  id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL,
  kind TEXT NOT NULL,                  -- jdbc-oracle | jdbc-postgres | ...
  config_json TEXT NOT NULL,           -- non-secret fields
  secret_enc BLOB,                     -- AES-GCM encrypted credentials
  role TEXT NOT NULL,                  -- SOURCE | SINK | BOTH
  max_concurrency INT DEFAULT 2,
  created_at TIMESTAMP, updated_at TIMESTAMP
);

CREATE TABLE job (
  id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL,
  source_conn_id TEXT NOT NULL REFERENCES connection(id),
  source_object TEXT NOT NULL,         -- schema.table_or_view
  key_columns_json TEXT NOT NULL,
  columns_json TEXT NOT NULL,          -- include/exclude/rename/coercions
  mode TEXT NOT NULL,                  -- WATERMARK | SNAPSHOT_DIFF | HYBRID
  watermark_column TEXT, overlap_seconds INT DEFAULT 300,
  schedule_kind TEXT NOT NULL,         -- CRON | INTERVAL
  schedule_expr TEXT NOT NULL,         -- cron expression or duration (e.g. 30s, minimum 10s)
  fixed_delay BOOLEAN DEFAULT FALSE,   -- measure interval from run end instead of start
  jitter_pct INT DEFAULT 10,
  reconcile_cron TEXT,
  batch_size INT DEFAULT 10000, fetch_size INT DEFAULT 5000,
  max_delete_percent INT DEFAULT 20,
  status TEXT NOT NULL,                -- ACTIVE | PAUSED | NEEDS_REVIEW
  version INT NOT NULL,                -- optimistic locking
  created_at TIMESTAMP, updated_at TIMESTAMP
);

CREATE TABLE job_sink (
  id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES job(id),
  sink_conn_id TEXT REFERENCES connection(id),
  sink_type TEXT NOT NULL,             -- jdbc | webhook | kafka | ...
  config_json TEXT NOT NULL,           -- target table, templates, topic, etc.
  delete_mode TEXT DEFAULT 'SOFT',     -- SOFT (default) | HARD | IGNORE
  soft_delete_column TEXT DEFAULT '_deleted_at',   -- timestamp flag
  soft_delete_flag_column TEXT DEFAULT '_is_deleted',  -- boolean flag, written together with the timestamp
  on_dead_letter TEXT DEFAULT 'PAUSE',
  status TEXT NOT NULL                 -- ACTIVE | PAUSED | RESNAPSHOT_REQUIRED
);

CREATE TABLE job_cursor (
  job_id TEXT PRIMARY KEY REFERENCES job(id),
  watermark_value TEXT, last_key_json TEXT, updated_at TIMESTAMP
);

CREATE TABLE row_index (
  job_id TEXT NOT NULL, key_hash BLOB NOT NULL, key_json TEXT NOT NULL,
  row_hash BLOB NOT NULL, last_seen_run TEXT,
  row_json BLOB,                       -- optional, compressed; only for upsert-with-flag sinks
  PRIMARY KEY (job_id, key_hash)
);
CREATE INDEX row_index_last_seen ON row_index(job_id, last_seen_run);

CREATE TABLE run (
  id TEXT PRIMARY KEY, job_id TEXT NOT NULL, kind TEXT NOT NULL,  -- POLL | RECONCILE | RESNAPSHOT
  started_at TIMESTAMP, finished_at TIMESTAMP, state TEXT NOT NULL,
  rows_read BIGINT, upserts BIGINT, deletes BIGINT, unchanged BIGINT,
  error TEXT
);

CREATE TABLE outbox_batch (
  job_id TEXT NOT NULL, batch_id BIGINT NOT NULL, payload BLOB NOT NULL,  -- compressed
  created_at TIMESTAMP, PRIMARY KEY (job_id, batch_id)
);

CREATE TABLE sink_offset (
  job_sink_id TEXT PRIMARY KEY, last_acked_batch BIGINT NOT NULL,
  attempts INT DEFAULT 0, next_retry_at TIMESTAMP, last_error TEXT
);

CREATE TABLE dead_letter (
  id TEXT PRIMARY KEY, job_sink_id TEXT NOT NULL, batch_id BIGINT NOT NULL,
  payload BLOB NOT NULL, error TEXT, created_at TIMESTAMP, resolved_at TIMESTAMP
);

CREATE TABLE audit_log (
  id INTEGER PRIMARY KEY, at TIMESTAMP, actor TEXT, action TEXT, entity TEXT, detail_json TEXT
);

CREATE TABLE app_user (
  id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,         -- Argon2id
  role TEXT NOT NULL,                  -- viewer | operator | admin
  must_change_password BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP, updated_at TIMESTAMP
);

CREATE TABLE scheduler_lease (
  name TEXT PRIMARY KEY,               -- 'scheduler'
  holder TEXT NOT NULL, expires_at TIMESTAMP NOT NULL
);
```

**SQLite settings:** WAL mode, `busy_timeout` of a few seconds, and all state writes routed through a single writer so concurrent job runs never contend for the write lock.

---

## 8. Sinks

### 8.1 JDBC sink (PostgreSQL, Oracle)
- `init`: optionally create the target table from the canonical schema (`autoCreate`) and always verify that key columns exist.
- `apply`: load the batch into a staging table (PG: `COPY`; Oracle: array-bound `INSERT`), then
  - PG: `INSERT … ON CONFLICT (pk) DO UPDATE`
  - Oracle: `MERGE INTO … USING staging … `
  - deletes: **soft by default**. The sink writes **both** flags on the target row in the same statement: `_deleted_at = now()` (nullable `TIMESTAMP`, says when) and `_is_deleted = TRUE` (`BOOLEAN NOT NULL DEFAULT FALSE`, easy to filter on; on Oracle it is `CHAR(1) DEFAULT 'N' NOT NULL` holding `'Y'` or `'N'`, with `CHECK (_is_deleted IN ('Y','N'))`). Both column names are configurable and are added automatically when `autoCreate` is on. `HARD` (`DELETE … WHERE key IN (…)`) and `IGNORE` are opt-in per sink.
  - **Resurrection:** if a previously deleted key reappears in the source, the upsert sets `_deleted_at = NULL` and `_is_deleted = FALSE`.
  - **Convenience view:** with `autoCreate`, the sink can also create `<table>_active` (`WHERE _is_deleted = FALSE`) so consumers can query live rows without remembering the flag.
  - **Webhook equivalent:** deletes are sent as `op: "delete"` events. Optionally `deleteAs: "upsert-with-flag"` sends the last known row with `_is_deleted: true` for consumers that prefer state over events.
- Each batch is applied in one target transaction.
- Optional `_row_hash` column on the target to skip no-op updates and reduce WAL churn.

### 8.2 Webhook sink
- Config: URL, method, headers, auth (none, bearer, basic, HMAC signature), timeout, batch mode (`per-row` or `per-batch`, max rows/bytes), payload template (JSON, with a default envelope below).
- Headers: `Idempotency-Key`, `X-DataSync-Job`, `X-DataSync-Signature` (HMAC-SHA256 of body).
- Success: any 2xx. 429 and 5xx are retried with backoff (honour `Retry-After`). Other 4xx go straight to dead letter.
- Default envelope:

```json
{
  "job": "customers_sync",
  "batchId": 1042,
  "object": "HR.CUSTOMERS_V",
  "changes": [
    { "op": "upsert", "key": {"customer_id": 7}, "row": {"customer_id": 7, "customer_name": "Ada"},
      "idempotencyKey": "9f2c…", "observedAt": "2026-10-22T10:00:00Z" },
    { "op": "delete", "key": {"customer_id": 9}, "idempotencyKey": "41ab…" }
  ]
}
```

### 8.3 Broker sinks (later)
Kafka, NATS JetStream, RabbitMQ and SNS/SQS. Message key = canonical key, idempotency key in a header. Same retry and dead-letter rules.

### 8.4 External plugins (later)
Jar-based plugins placed in `plugins/` and discovered by `ServiceLoader` in an isolated classloader. A gRPC/subprocess option can follow if non-JVM plugins are needed.

---

## 9. REST API (OpenAPI-documented)

All endpoints under `/api/v1`, JSON, session cookie or bearer token.

| Area | Endpoints |
|---|---|
| Connections | `GET/POST /connections`, `GET/PUT/DELETE /connections/{id}`, `POST /connections/{id}/test`, `GET /connections/{id}/objects?schema=…`, `GET /connections/{id}/objects/{name}/schema` |
| Jobs | `GET/POST /jobs`, `GET/PUT/DELETE /jobs/{id}`, `POST /jobs/{id}/pause`, `/resume`, `/run`, `/reconcile`, `/resnapshot?sink=…`, `POST /jobs/validate` (dry run, previews sample rows and mapping) |
| Sinks | `GET/POST /jobs/{id}/sinks`, `PUT/DELETE /jobs/{id}/sinks/{sinkId}`, `POST …/test` |
| Runs | `GET /jobs/{id}/runs`, `GET /runs/{id}` |
| Dead letters | `GET /dead-letters`, `POST /dead-letters/{id}/retry`, `POST /dead-letters/{id}/discard` |
| Monitoring | `GET /overview`, `GET /jobs/{id}/status`, `GET /events` (SSE live updates) |
| Config as code | `GET /export` (YAML), `POST /import` (YAML, supports `?dryRun=true`) |
| Admin | `GET/POST /users`, `GET/PUT /settings`, `GET /audit` |
| Ops | `/metrics`, `/q/health/live`, `/q/health/ready` |

Secrets are write-only: the API accepts them but never returns them.

---

## 10. GUI design (React)

### 10.1 Frontend stack
- React 18, TypeScript, Vite
- TanStack Query (server state), React Router, React Hook Form + Zod (forms and validation)
- Component library: shadcn/ui (Radix + Tailwind)
- Charts: Recharts
- Live updates: Server-Sent Events (`/api/v1/events`) feeding the TanStack Query cache
- API client generated from the OpenAPI spec (`openapi-typescript`)
- Tests: Vitest + React Testing Library, Playwright for end-to-end flows

### 10.2 Pages

| Page | Content |
|---|---|
| **Dashboard** | Tiles: active jobs, failing jobs, total lag, rows synced (24h), dead-letter count. Table of jobs with status badge, last run, next run, lag. |
| **Connections** | List, create/edit wizard (type-specific form), **Test connection**, role tag (source/sink). |
| **Job list** | Filter by status, source, sink. Bulk pause/resume. |
| **Job wizard** | 1) Pick source connection and browse schema → table/view. 2) Confirm key columns (auto-suggested from PK), choose columns, rename. 3) Change detection: mode, watermark column, overlap, schedule, reconcile cron, delete guard. 4) Add sinks (DB target with auto-create preview, webhook, etc.). 5) **Dry run** preview of sample rows and generated target DDL, then save. |
| **Job detail** | Tabs: *Overview* (status, cursor, lag chart), *Runs* (history with counts and errors), *Sinks* (per-sink offset, backlog, last error), *Config* (YAML view), *Audit*. Actions: Run now, Reconcile, Pause, Resnapshot sink. |
| **Dead letters** | Filter by job/sink, payload viewer, retry/discard, bulk actions. |
| **Settings** | Users and roles, OIDC, alert channels (email, webhook), retention, encryption-key status. |

### 10.3 UX principles
- Every destructive or expensive action (resnapshot, discard, enable hard-delete) needs a confirmation that states the effect.
- Errors show a human message plus an expandable technical detail.
- Dry run before first save is the default path, not an advanced option.
- Roles: `viewer` (read), `operator` (run/pause/retry), `admin` (edit connections, jobs, users).

---

## 11. Security

- **Credentials:** AES-256-GCM at rest. Master key supplied through `DATASYNC_MASTER_KEY` (or file or KMS plugin). Never logged, never returned by the API.
- **Auth:** local users only in v1 (Argon2id password hashes, roles `viewer`/`operator`/`admin`, forced password change for the bootstrap admin). OIDC is deferred. The auth layer sits behind an `Authenticator` interface so OIDC can be added without API changes.
- **Transport:** TLS terminated at the app or an ingress. Source and sink connections support TLS and, for Oracle, TCPS.
- **Source safety:** documentation recommends a dedicated read-only account. A per-connection concurrency cap, query timeout and fetch-size limit are enforced.
- **Webhook sink:** SSRF protection (configurable allow/deny of private ranges), HMAC signing, TLS verification on by default.
- **Audit:** all config changes and operator actions are recorded.

---

## 12. Observability

- **Metrics** (Micrometer, Prometheus format):
  `datasync_run_duration_seconds{job}`, `datasync_rows_total{job,kind}`, `datasync_lag_seconds{job}`, `datasync_sink_backlog_batches{job,sink}`, `datasync_sink_errors_total{job,sink}`, `datasync_dead_letters{job,sink}`.
- **Logging:** structured JSON with `jobId`, `runId`, `sinkId` in the MDC.
- **Alerts:** built-in rules (job failed N times, lag over threshold, dead letter created, delete guard tripped) sent to email or webhook. External alerting can use the Prometheus metrics instead.
- **Retention:** run history (default 30 days) and trimmed outbox are pruned by a background task.

---

## 13. Configuration as code

Jobs and connections can be exported and imported as YAML, so the configuration can live in Git. Secrets are referenced by `${env:NAME}` rather than inlined.

```yaml
connections:
  - name: oracle_prod
    kind: jdbc-oracle
    role: source
    url: jdbc:oracle:thin:@//ora-host:1521/ORCLPDB1
    username: sync_ro
    password: ${env:ORA_PASSWORD}
  - name: pg_dw
    kind: jdbc-postgres
    role: sink
    url: jdbc:postgresql://pg-host:5432/dw
    username: sync_rw
    password: ${env:PG_PASSWORD}

jobs:
  - name: customers_sync
    source: { connection: oracle_prod, object: HR.CUSTOMERS_V }
    keys: [CUSTOMER_ID]
    columns: { include: "*", rename: { CUST_NAME: customer_name } }
    mode: HYBRID
    watermark: { column: UPDATED_AT, overlap: 5m }
    schedule: "*/5 * * * *"        # or: every: 30s (minimum 10s)
    reconcile: { cron: "0 2 * * *", maxDeletePercent: 20 }
    batchSize: 10000
    sinks:
      - type: jdbc
        connection: pg_dw
        target: public.customers
        autoCreate: true
        deleteMode: SOFT
      - type: webhook
        url: https://hooks.example.com/customers
        auth: { type: hmac, secret: ${env:HOOK_SECRET} }
        batch: { maxRows: 500 }
```

---

## 14. Deployment

- **Artifact:** one fat jar plus a Docker image (JRE 21 slim base). Volume mount for `/data` (SQLite file) and `/plugins`.
- **Sizing guidance:** 256 MB heap-limited container is sufficient for several jobs at 10k batch size. Raise limits for large snapshot-diff jobs.
- **Scaling:** v1 is a single instance, with no HA work planned. The scheduler is written against a lease abstraction (a `scheduler_lease` row with a single-node implementation in v1), so a PostgreSQL state store plus a hot standby can be added later without redesign.
- **Backup:** SQLite file snapshot (or `VACUUM INTO`). The YAML export is the configuration backup.

```yaml
# docker-compose.yml (example)
services:
  datasync:
    image: datasync:latest
    ports: ["8080:8080"]
    environment:
      DATASYNC_MASTER_KEY: ${DATASYNC_MASTER_KEY}
      JAVA_OPTS: "-Xmx192m"
    volumes:
      - ./data:/data
      - ./plugins:/plugins
```

---

## 15. Testing strategy

| Level | Approach |
|---|---|
| Unit | Canonical type mapping, hashing determinism, watermark and keyset SQL generation per dialect, diff merge logic |
| Integration | Testcontainers: PostgreSQL, Oracle (`gvenzl/oracle-free`), WireMock for webhook. Verify create, update, delete, late-commit, overlap and restart scenarios |
| Contract | A reusable `SinkContractTest` and `SourceContractTest` that every connector module must pass (idempotent re-apply, delete handling, null/empty semantics) |
| Fault injection | Kill the process mid-batch, sink outage, network partition. Assert no data loss and convergence after recovery |
| End-to-end UI | Playwright: create connections, build a job through the wizard, run it, see results on the dashboard |
| Performance | 1M and 10M row snapshot-diff, measure memory and duration, tune chunk and fetch sizes |

---

## 16. Roadmap

| Milestone | Scope | Outcome |
|---|---|---|
| **M1: Engine core + snapshot-diff** | `api` module, canonical model and hashing, Oracle dialect (source), PostgreSQL dialect (sink, soft delete, auto-create), hash index, `SNAPSHOT_DIFF` mode with delete guard, SQLite state, YAML-driven CLI | Oracle → PG sync of the default mode (including deletes) runs from a file |
| **M2: Watermark + scheduler** | `WATERMARK` and `HYBRID` modes, reconcile cron, full scheduler (cron/interval, skip-on-overlap, jitter, concurrency cap, lease) | Large tables synced incrementally on a schedule |
| **M3: Webhook sink** | Sink SPI hardening, retry, dead letter, HMAC | Proves the abstraction against an event sink |
| **M4: API + GUI v1** | REST API, auth, React: connections, job wizard, dashboard, runs, dead letters | Fully managed through the browser |
| **M5: Reverse direction** | PG source dialect, Oracle sink dialect | Validates both directions of the SPI |
| **M6: Outbox + multi-sink** | Shared outbox, per-sink offsets, resnapshot flow | Independent sink progress |
| **M7: Hardening** | Metrics, alerts, local-user management, audit, export/import, docs | Production-ready v1 |
| **M8: Transformations** (nice to have) | Built-in column transforms (mask, hash, constant, trim, concat), then a sandboxed expression language, preview in wizard | Masking and derived columns |
| **Later** | Kafka/NATS sinks, plugin loader, SQL Server/MySQL dialects, OIDC, HA with PG state store | Ecosystem growth |

---

## 17. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Source has no reliable `updated_at` | Snapshot-diff mode, with chunked scans and an optional range-hash narrowing optimisation later |
| Large objects make the hash index heavy | HYBRID mode, PG state store option, per-job index size shown in UI |
| Late-committing source transactions | Overlap window plus idempotent delivery plus periodic reconcile |
| Partial source read causing mass deletes | Delete guard with `NEEDS_REVIEW` state and alert |
| Type mismatches across vendors | Central canonical type map, per-job coercion overrides, dry-run preview, contract tests |
| SQLite as single-writer bottleneck | WAL mode, `busy_timeout`, single writer, batched writes, option to move state store to PostgreSQL |
| Event-sink consumers receive duplicates | Documented at-least-once semantics, deterministic idempotency keys |
| Scope creep into a full ETL tool | Non-goals stated above. Transformations limited to select/rename/coerce in v1 |

---

## 18. Decisions and remaining questions

### Decisions (resolved)

| # | Question | Decision | Effect on the design |
|---|---|---|---|
| 1 | Native image or JVM? | JVM mode, about 200 MB footprint is acceptable | Native image dropped. SQLite and dynamic plugins used freely |
| 2 | Column transformations | Nice to have, not required for v1 | Hook defined in 4.4, scheduled as M8 |
| 3 | Largest object size | About 10K rows for now | `SNAPSHOT_DIFF` is the default mode. Hash index is trivial. `HYBRID` kept for growth |
| 4 | Delete handling | Soft delete (flag column) by default | `delete_mode` defaults to `SOFT` with `_deleted_at`, resurrection clears the flag |
| 5 | High availability | Single node for v1, HA later | Lease abstraction kept, no HA implementation |
| 6 | Authentication | Local users are enough | OIDC deferred behind an `Authenticator` interface |

### Additional decisions (from the second round)

| # | Question | Decision | Effect on the design |
|---|---|---|---|
| 7 | Soft-delete flag type | **Both**: `_deleted_at` timestamp and `_is_deleted` boolean | Written together, cleared together on resurrection, optional `_active` view (8.1) |
| 8 | Poll interval | 5 minutes by default, faster likely later | Interval scheduling with a 10 s minimum, skip-on-overlap and jitter (section 5A) |
| 9 | Target tables | The service may create them | `autoCreate` is the default path. Existing-table adoption is still supported but not the focus |
| 10 | Webhook consumers | Under your control | Consumers can be required to dedupe on `Idempotency-Key`. A reference consumer snippet and the `upsert-with-flag` option are provided. Third-party hardening (per-consumer rate limits, signature rotation) is deferred |
| 11 | Oracle boolean flag | `CHAR(1)` with `'Y'` / `'N'` | Oracle dialect maps the boolean flag to `CHAR(1) DEFAULT 'N' NOT NULL` plus a check constraint. PostgreSQL keeps a native `BOOLEAN` |
| 12 | Flag names | `_deleted_at` and `_is_deleted` confirmed | Defaults stay, still overridable per job |

### Implementation review (v0.5)

| # | Finding | Change |
|---|---|---|
| 13 | Snapshot-diff merge-join could not work: source PK order and `key_hash` order differ | Lookup-and-mark algorithm using `last_seen_run` (5.2) |
| 14 | Watermark overlap relied on the hash index, which was scheduled for M2 | Hash index built in M1 for all modes (5.3) |
| 15 | M1 built `WATERMARK` although `SNAPSHOT_DIFF` is the default mode | M1 and M2 swapped (16) |
| 16 | `upsert-with-flag` needs the last known row, but the index stored only hashes | Optional `row_json` column in `row_index` (5.3, 7) |
| 17 | Schema lacked users, the scheduler lease and interval scheduling fields | `app_user`, `scheduler_lease`, `schedule_kind`/`schedule_expr`/`fixed_delay`/`jitter_pct` added (7) |
| 18 | The Quarkus scheduler and Agroal pools are fixed at build time | Own scheduler and runtime HikariCP pools for source and sink connections (2) |
| 19 | Outbox milestone stated as M5 in section 6 but M6 in the roadmap | Unified on M6 |

### Remaining questions

None. The design is ready for implementation starting at M1.

> **Note on Oracle identifiers:** Oracle accepts a leading underscore only in quoted identifiers, which are then case-sensitive and must be quoted everywhere. The Oracle dialect therefore always quotes these two columns (`"_DELETED_AT"`, `"_IS_DELETED"`) and uppercases the names by default. If this proves awkward for other tools querying the table, the job config can override the names, for example to `DELETED_AT` and `IS_DELETED`.