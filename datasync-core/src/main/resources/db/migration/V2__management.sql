-- Managed configuration (M4). Job and sink definitions are kept as JSON in the shape of the YAML config,
-- with connections referenced by id.
CREATE TABLE connection (
  id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL,
  kind TEXT NOT NULL,                  -- jdbc-oracle | jdbc-postgres | ...
  role TEXT NOT NULL,                  -- SOURCE | SINK | BOTH
  config_json TEXT NOT NULL,           -- non-secret fields: url, username
  secret_enc BLOB,                     -- AES-GCM encrypted password
  max_concurrency INT DEFAULT 2,
  created_at TIMESTAMP, updated_at TIMESTAMP
);

CREATE TABLE job (
  id TEXT PRIMARY KEY, name TEXT UNIQUE NOT NULL,
  config_json TEXT NOT NULL,
  status TEXT NOT NULL,                -- ACTIVE | PAUSED | NEEDS_REVIEW
  version INT NOT NULL,                -- optimistic locking
  created_at TIMESTAMP, updated_at TIMESTAMP
);

CREATE TABLE app_user (
  id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,         -- Argon2id
  role TEXT NOT NULL,                  -- VIEWER | OPERATOR | ADMIN
  must_change_password BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP, updated_at TIMESTAMP
);

CREATE TABLE audit_log (
  id INTEGER PRIMARY KEY, at TIMESTAMP, actor TEXT, action TEXT, entity TEXT, detail_json TEXT
);
CREATE INDEX audit_entity ON audit_log(entity, at);
