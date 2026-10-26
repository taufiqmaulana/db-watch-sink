# DataSync

Polling-based sync of database tables and views to other databases. DataSync reads a source with a plain
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
| M2: watermark and hybrid modes, reconcile | Not started |
| M3: webhook sink, retries and dead letters | Not started |
| M5: Oracle as a sink (MERGE from a global temporary table; Oracle 11g and later) | Done |
| M6 to M8: outbox and multi-sink fan-out, hardening, transformations | Not started |

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
| `DATASYNC_DATA_DIR` | `data` | Holds the SQLite state store: configuration, run history, hash index, users and audit log |
| `DATASYNC_MASTER_KEY` | generated into `<data dir>/master.key` | AES-256 key (32 bytes, base64) that encrypts stored database passwords. Back it up: without it, stored credentials cannot be decrypted |
| `DATASYNC_ADMIN_PASSWORD` | random, printed to the log | Password of the `admin` user created on first start |
| `QUARKUS_HTTP_PORT` | `8080` | HTTP port |

Other endpoints:

- REST API: `/api/v1`, described by the OpenAPI document at `/q/openapi`
- Health checks: `/q/health/live` and `/q/health/ready`

### Using the UI

1. **Connections**: add the source and target databases, then click **Test**. Sources only need an account
   with `SELECT`.
2. **Jobs, then New job**: pick the source table or view, the columns and the key column, the delete guard and
   the sinks. The last step is a dry run that shows the column mapping and sample rows. A job can be saved
   only after its dry run succeeds.
3. Active jobs run on their schedule: every 5 minutes by default, or any interval of at least 10 seconds or a
   cron expression (server time zone). Click **Run now** on the job page to run one immediately. A scheduled run
   is skipped while the previous one is still going. Missing target tables are created on the first run.

Deletes are **soft** by default: the target row stays, with `_deleted_at` and `_is_deleted` set. If the row
comes back at the source, both are cleared. When a run would delete more than the job's **delete guard**
percentage, it stops before deleting and the job is marked **Needs review**. Check the source, then use
**Apply deletes** on the job page if the deletes are real.

Roles:

- **Viewer** sees everything except users.
- **Operator** can also run, pause and resume jobs, and apply deletes.
- **Admin** can also manage connections, jobs and users.

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
| `datasync-core` | Snapshot-diff logic against fake connectors, config loading |
| `datasync-dialect-*` | SQL generation, type mapping, `COPY` escaping |
| `datasync-cli` | End to end through the CLI: Postgres to Postgres, and Oracle to Postgres (Docker) |
| `datasync-server` | The REST API end to end with a real Postgres (Docker), password hashing |
| `datasync-ui` | Job form conversion and the YAML view (`npm test`) |

## Project layout

```
datasync-api/               SPI interfaces and the canonical data model (no dependencies)
datasync-core/              engine: config, snapshot diff, hash index, SQLite state store and migrations
datasync-jdbc/              generic JDBC source and sink, dialect SPI
datasync-dialect-oracle/    Oracle dialect (source and sink, 11g and later)
datasync-dialect-postgres/  PostgreSQL dialect (source and sink, COPY-based loading)
datasync-cli/               YAML-driven command-line runner
datasync-server/            Quarkus app: REST API, sessions and roles, run executor, live events (SSE)
datasync-ui/                React UI (Vite, TypeScript, TanStack Query, Tailwind), bundled into the server
```

New database vendors are added as a `SqlDialect` implementation. New sink types are added as a
`SinkProvider`. Both are discovered with `ServiceLoader`, so the engine and the UI need no changes.
