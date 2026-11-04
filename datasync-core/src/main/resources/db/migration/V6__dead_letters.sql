-- The last row delivered per key, kept only for jobs with a sink that sends deleted rows
-- (deleteAs: upsert-with-flag, design 5.3). Canonical JSON under target column names.
ALTER TABLE row_index ADD COLUMN row_json TEXT;

-- Batches a sink could not take after its retries (design 6). The payload is the batch as JSON, with the
-- target schema, so it can be replayed to that sink later.
CREATE TABLE dead_letter (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  sink_id TEXT NOT NULL,
  batch_id BIGINT NOT NULL,
  change_count INT NOT NULL,
  payload TEXT NOT NULL,
  error TEXT,
  attempts INT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  resolved_at TIMESTAMP,
  resolution TEXT                      -- RETRIED | DISCARDED
);
CREATE INDEX dead_letter_open ON dead_letter(resolved_at, created_at);
