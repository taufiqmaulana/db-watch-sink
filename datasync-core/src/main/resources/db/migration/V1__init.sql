CREATE TABLE run (
  id TEXT PRIMARY KEY, job_id TEXT NOT NULL, kind TEXT NOT NULL,  -- POLL | RECONCILE | RESNAPSHOT
  started_at TIMESTAMP, finished_at TIMESTAMP, state TEXT NOT NULL, -- RUNNING | SUCCEEDED | FAILED | NEEDS_REVIEW
  rows_read BIGINT, upserts BIGINT, deletes BIGINT, unchanged BIGINT,
  error TEXT
);
CREATE INDEX run_job ON run(job_id, started_at);

CREATE TABLE row_index (
  job_id TEXT NOT NULL, key_hash BLOB NOT NULL, key_json TEXT NOT NULL,
  row_hash BLOB NOT NULL, last_seen_run TEXT,
  PRIMARY KEY (job_id, key_hash)
);
CREATE INDEX row_index_last_seen ON row_index(job_id, last_seen_run);
