# DataSync

Polling-based sync of database tables and views to other databases and to webhooks. DataSync reads a source with a plain
read-only `SELECT` account, detects inserts, updates and deletes, and applies them to one or more sinks. It
needs no triggers, LogMiner or replication slots on the source. A web UI manages connections and jobs and
shows their runs.

The full design is in [Design.md](Design.md).

## Status

| Milestone | State |
|---|---|
| M1: engine core and snapshot diff (Oracle and PostgreSQL sources, PostgreSQL sink, YAML CLI) | Done |
| M4: REST API and management UI | Done |
| M2: scheduler (cron or interval, skip-on-overlap, jitter, per-connection concurrency cap, lease) | Done in the server; the CLI still runs each job once |
| M2: watermark and hybrid modes, reconcile | Done |
| M3: webhook sink, retries with backoff, dead letters, HMAC signing | Done |
| M5: Oracle as a sink (MERGE from a global temporary table; Oracle 11g and later) | Done |
| M7: hardening (metrics, alerts, retention, YAML export and import, JSON logs) | Done |
| M6: outbox, independent sink delivery, resnapshot | Done |
| M8: transformations | Not started |

## Requirements

- Java 21. Maven is not needed: use the included wrapper (`./mvnw`, or `mvnw.cmd` on Windows).
- Node.js 22 or later. The build runs `npm ci` for the UI.
- Docker, only for the integration tests (Testcontainers). The Oracle test pulls `gvenzl/oracle-free`, about 1 GB.

## Build

```sh
./mvnw install                 # everything, with all tests
./mvnw install -DskipTests     # skip tests (Java and UI)
```

This produces:

- `datasync-server/target/quarkus-app/`: the server with the UI built in
- `datasync-cli/target/datasync.jar`: the standalone command-line runner

## Run the server

```sh
export DATASYNC_MASTER_KEY=$(openssl rand -base64 32)   # keep this safe
export DATASYNC_ADMIN_PASSWORD='choose-a-password'
java -jar datasync-server/target/quarkus-app/quarkus-run.jar
```

Open http://localhost:8080 and sign in as `admin`. You are asked to choose a new password at first sign-in.

| Variable | Default | Purpose |
|---|---|---|
| `DATASYNC_DATA_DIR` | `data` | Holds the SQLite state store: configuration, run history, hash index, outbox, users and audit log |
| `DATASYNC_MASTER_KEY` | required | AES-256 key (32 bytes, base64) that encrypts stored passwords and secrets. Keep it apart from backups of the data directory: without it, stored credentials cannot be decrypted. The server refuses to start without it |
| `DATASYNC_ALLOW_KEY_FILE` | `false` | Set to `true` to keep the key in `<data dir>/master.key` instead (generated on first start). Fine for trials; in production the key would end up in every copy of the data. Installs that already use the file can set `DATASYNC_MASTER_KEY` to its content |
| `DATASYNC_ADMIN_PASSWORD` | random, printed to the log | Password of the `admin` user created on first start |
| `DATASYNC_LOG_JSON` | `false` | Log as JSON lines, with `jobId` and `runId` on the lines of a run |
| `QUARKUS_MAILER_HOST`, `_PORT`, `_FROM`, `_USERNAME`, `_PASSWORD`, `_TLS` | none | SMTP server for alert emails. Set them before adding email recipients under **Settings, Alerts and retention** |
| `DATASYNC_WEBHOOK_BLOCK_PRIVATE_NETWORKS` | `false` | Also refuse webhook URLs on loopback and private networks. Link-local addresses (such as cloud metadata at 169.254.169.254) are always refused |
| `QUARKUS_HTTP_PORT` | `8080` | HTTP port |

Other endpoints:

- REST API: `/api/v1`, described by the OpenAPI document at `/q/openapi`
- Health checks: `/q/health/live` and `/q/health/ready`
- Prometheus metrics: `/metrics`. Like the health checks, it needs no sign-in; restrict it at your proxy if needed.

| Metric | Meaning |
|---|---|
| `datasync_run_duration_seconds{job}` | Run durations (count, sum, max) |
| `datasync_rows_total{job,kind}` | Rows read, upserted, deleted and unchanged (`kind`: read, upsert, delete, unchanged) |
| `datasync_lag_seconds{job}` | Time since the start of the job's last successful run: how old the synced data may be |
| `datasync_sink_errors_total{job,sink}` | Failed delivery attempts, retried or not |
| `datasync_sink_backlog_batches{job,sink}` | Batches queued for the sink |
| `datasync_dead_letters{job,sink}` | Dead-lettered batches waiting to be retried or discarded |
| `datasync_skipped_ticks_total{job}` | Scheduled runs skipped because the previous one was still going |

### Deploy with Docker

```sh
./mvnw install -DskipTests
docker build -t datasync .
export DATASYNC_MASTER_KEY=$(openssl rand -base64 32)   # store it in your secret manager
docker compose up -d                                    # see docker-compose.yml
```

The image runs as a non-root user, keeps its state in the `/data` volume and listens on 8080. Run one instance per
data volume (the state store is a single SQLite file), behind a reverse proxy that terminates TLS and sets HSTS.
Use `/q/health/ready` for load balancer and orchestrator checks; it reports down when the state store does not
answer.

The `Docker image` GitHub workflow (`.github/workflows/docker-publish.yml`) publishes the image to Docker Hub: every
push to `master` as `:master`, and every `vX.Y.Z` tag as `:X.Y.Z`, `:X.Y`, `:X` and `:latest`. It needs the repository
secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`; the image is `<username>/datasync` unless the repository variable
`DOCKERHUB_REPOSITORY` says otherwise.

Security built in: passwords hashed with Argon2id, stored credentials encrypted with AES-GCM, an HttpOnly,
SameSite=Strict session cookie (8 hours idle), sign-in refused for 15 minutes after 5 failed attempts for a username
or 20 from one address (recorded in the audit log), and a strict Content-Security-Policy, `X-Frame-Options: DENY`
and `nosniff` on every response. Keep `/metrics` and `/q/openapi` internal at the proxy.

### Sizing

Measured with 50,000 rows of about 400 bytes, a PostgreSQL sink and a webhook sink, on a laptop
(`LoadTest`, `./mvnw -pl datasync-cli test -Dtest=LoadTest -Dload=true -DargLine=-Xmx256m`):

| Step | Time |
|---|---|
| First run: read and queue every row | 2.5 s |
| Its delivery to PostgreSQL (50,000 rows) | 2.2 s |
| A run with nothing changed | 1.0 s |
| A run with 500 updates, 50 deletes and 50 inserts, and its delivery | 1.2 s |
| Webhook catching up on 4 queued batches (80 requests of 500 changes) | 1.0 s |

It also completes with a 128 MB heap. The state file was about 56 MB, mostly the hash index. At a 5-minute
interval a job of this size is idle more than 99% of the time; the default snapshot diff is the right mode up to
a few hundred thousand rows. A 512 MB container (as in docker-compose.yml) leaves room for several such jobs.

### Using the UI

1. **Connections**: add the source and target databases, and any webhooks, then click **Test**. Sources only
   need an account with `SELECT`.
2. **Jobs, then New job**: pick the source table or view, the columns and the key column, the delete guard and
   the sinks. The last step is a dry run that shows the column mapping and sample rows. A job can be saved
   only after its dry run succeeds.
3. Active jobs run on their schedule: every 5 minutes by default, or any interval of at least 10 seconds or a
   cron expression (server time zone). Click **Run now** on the job page to run one immediately. A scheduled run
   is skipped while the previous one is still going. Missing target tables are created on the first run.

Each job picks how it finds changes:

| Mode | Each run reads | Deletes | Use when |
|---|---|---|---|
| **Snapshot diff** (default) | The whole source | Detected every run | Up to a few hundred thousand rows, or no update column (typical for views) |
| **Watermark** | Rows whose watermark column (an update time or increasing id) is at or after the last value seen, minus an overlap window (default 5 min) | Never | Large tables with a reliable update column, where deletes do not matter |
| **Hybrid** | As Watermark, plus a full comparison (**reconcile**) on its own cron | At each reconcile | Large tables that need deletes and a safety net for missed updates |

Rows whose watermark is NULL are only picked up by reconciles. **Reconcile** on the job page (or
`--reconcile` on the CLI) runs one on demand. The job page warns when runs take more than half the time between them.

Columns can be converted to another type (**Sync as** in the wizard, `columns.coerce` in YAML), e.g. an Oracle
`NUMBER(1)` or `'Y'`/`'N'` flag to `BOOLEAN`. Oracle stores empty text as NULL; by default (`emptyStrings: AUTO`)
empty text becomes NULL whenever a sink is Oracle, so every target agrees. With **Skip rewriting unchanged rows**
(`rowHash: true`), a sink keeps a `_row_hash` column and leaves rows alone when a re-send would not change them.

Deletes are **soft** by default: the target row stays, with `_deleted_at` and `_is_deleted` set. If the row
comes back at the source, both are cleared. When a run would delete more than the job's **delete guard**
percentage, it stops before deleting and the job is marked **Needs review**. Check the source, then use
**Apply deletes** on the job page if the deletes are real.

### Webhooks

A webhook connection holds the endpoint URL and its authentication: none, a bearer token, basic, or an HMAC
secret. The secret is stored encrypted like database passwords. A webhook sink posts changes as JSON:

```json
{ "job": "customers_sync", "batchId": 42, "object": "HR.CUSTOMERS_V",
  "changes": [ { "op": "upsert", "key": { "CUSTOMER_ID": 7 }, "row": { "...": "..." },
                 "idempotencyKey": "9f2c...", "observedAt": "2026-11-04T07:19:07Z" },
               { "op": "delete", "key": { "CUSTOMER_ID": 8 }, "idempotencyKey": "...", "observedAt": "..." } ] }
```

- Each request carries `Idempotency-Key` (the same when a batch is re-sent) and `X-DataSync-Job`. With HMAC
  auth it also carries `X-DataSync-Signature: sha256=<hex HMAC-SHA256 of the body>`.
- Changes are sent up to 500 a request by default, or one a request.
- With **Send the row flagged as deleted** (`deleteAs: upsert-with-flag`), a delete is sent as an upsert of the
  last row seen with `"_is_deleted": true`, for consumers that only upsert.
- Any 2xx response is success. 429, 5xx, timeouts and refused connections are retried; `Retry-After` is honoured.
  Other responses are not retried.

### Sinks, retries and dead letters

A run writes the changes it finds to a queue (the job's outbox) in the same transaction that records them as seen.
Each sink then delivers from that queue on its own, in order, so a slow or failing sink holds up no other sink and
no run. The job page's **Sinks** tab shows each sink's status, how many batches are waiting, and its last error.

A failed batch is retried with growing waits (1 s, 2 s, 4 s and so on, up to 1 minute, with jitter), 5 attempts by
default. A batch that still fails goes to the **dead letters**. Then, by default, that sink is **paused** until an
operator clicks **Resume**, while its new changes keep queueing; with **Skip it and carry on**
(`onDeadLetter: SKIP`) it continues with the next batch. The **Dead letters** page shows each batch, its error and
its changes. **Retry** sends it again once the sink is fixed, and **Discard** drops it. Both are recorded in the
audit log.

A sink more than 1000 batches behind (`outbox.maxBatches`, **Sink backlog limit** in the wizard) stops and is
marked **Needs resnapshot**, so the queue stays bounded. **Resnapshot** on the Sinks tab drops what it was waiting
for and starts a run that sends it every source row, as for a newly added sink. It also helps after a target table
was emptied by hand.

The command line runs each job, then delivers to every sink, waiting through retries. It exits with 1 when a sink
is left paused or needing a resnapshot; its changes stay queued in the state file for the next run.

### Alerts, retention and configuration as YAML

Under **Settings**, admins choose who gets alerts (email addresses and a webhook URL; Slack and Teams incoming
webhooks work as is) and when: a job failed a number of runs in a row (3 by default), an active job has had no
successful run for some minutes (off by default), a batch went to the dead letters, or the delete guard stopped a
run. A sink that dead-letters a batch or needs a resnapshot is alerted under the dead-letter rule.
**Send test alert** checks the channels. Sent alerts are recorded in the audit log.

Finished runs and resolved dead letters older than the retention period (30 days by default) are removed hourly.
Each job keeps its latest run.

**Configuration** downloads every connection and job as YAML in the CLI's format, so it can be kept in Git or
moved to another server. Passwords are written as `${env:<CONNECTION>_PASSWORD}` references. Importing a file
first shows what it would create or update; connections and jobs are matched by name. An unset `${env:...}`
password keeps the stored one. The API equivalents are `GET /api/v1/export` and
`POST /api/v1/import?dryRun=true` with the YAML as the body.

For a full backup, copy the data directory while the server is stopped, and keep `DATASYNC_MASTER_KEY` in your
secret manager, not next to the copy.

Roles:

- **Viewer** sees everything except users and alert settings.
- **Operator** can also run, pause and resume jobs, apply deletes, retry or discard dead letters, and resume or
  resnapshot sinks.
- **Admin** can also manage connections, jobs, users and settings, and import configuration.

## Run from the command line

The CLI runs jobs defined in a YAML file once each, with no server needed. See
[datasync.example.yaml](datasync.example.yaml). Credentials can be written as `${env:NAME}`, so they stay
out of the file.

```sh
java -jar datasync-cli/target/datasync.jar run datasync.yaml [--job NAME] [--state FILE] [--allow-deletes]
```

| Exit code | Meaning |
|---|---|
| 0 | Every run succeeded |
| 1 | A run failed |
| 2 | The delete guard stopped a run. Re-run with `--allow-deletes` once the deletes are confirmed |

The CLI keeps its own state file (`data/datasync.db` by default). Jobs in the YAML file and jobs created in
the UI are separate.

## Development

Run the backend and the UI dev server in two terminals:

```sh
# terminal 1: API on :8080, reloads Java changes on the next request
./mvnw install -DskipTests -Dexec.skip      # once, and again after changing core, jdbc or dialect code
./mvnw -pl datasync-server quarkus:dev

# terminal 2: UI on :5173 with hot reload; forwards /api to :8080
cd datasync-ui
npm ci
npm run dev
```

In dev mode:

- Sign in as `admin` / `admin-password`.
- State lives in `datasync-server/target/dev-data`, so `./mvnw clean` starts fresh.
- The Quarkus Dev UI is at http://localhost:8080/q/dev-ui.

`-Dexec.skip` skips the UI's npm build, which dev mode doesn't need.

### API types

The UI's API types (`datasync-ui/src/api/schema.d.ts`) are generated from the server's OpenAPI document.
After changing a REST resource or its records, regenerate them:

```sh
./mvnw -pl datasync-server package -DskipTests   # writes target/openapi/openapi.yaml
cd datasync-ui && npm run gen:api
```

### Tests

| Where | What it covers |
|---|---|
| `datasync-core` | Snapshot-diff and watermark logic against fake connectors, config loading |
| `datasync-dialect-*` | SQL generation, type mapping, `COPY` escaping |
| `datasync-sink-webhook` | Envelope, auth headers and HMAC, retryable and permanent failures, address blocking, against a local HTTP server |
| `datasync-cli` (`LoadTest`, opt-in) | The sizing figures above |
| `datasync-cli` | End to end through the CLI: Postgres to Postgres (snapshot and hybrid), Oracle to Postgres, Postgres to Oracle, Postgres to a webhook (Docker) |
| `datasync-server` | The REST API end to end with a real Postgres (Docker): dead letters and their retry, alerts, metrics, export and import, retention; password hashing |
| `datasync-ui` | Job form conversion and the YAML view (`npm test`) |

## Project layout

```
datasync-api/               SPI interfaces and the canonical data model (no dependencies)
datasync-core/              engine: config, snapshot diff, hash index, SQLite state store and migrations
datasync-jdbc/              generic JDBC source and sink, dialect SPI
datasync-dialect-oracle/    Oracle dialect (source and sink, 11g and later)
datasync-dialect-postgres/  PostgreSQL dialect (source and sink, COPY-based loading)
datasync-sink-webhook/      webhook sink: JSON over HTTP, auth and HMAC signing, SSRF protection
datasync-cli/               YAML-driven command-line runner
datasync-server/            Quarkus app: REST API, sessions and roles, run executor, live events (SSE)
datasync-ui/                React UI (Vite, TypeScript, TanStack Query, Tailwind), bundled into the server
```

New database vendors are added as a `SqlDialect` implementation. New sink types are added as a
`SinkProvider`. Both are discovered with `ServiceLoader`, so the engine and the UI need no changes.
