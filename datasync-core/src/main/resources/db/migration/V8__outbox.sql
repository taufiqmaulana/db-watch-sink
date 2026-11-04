-- The outbox (design 6, M6): a run writes each chunk's changes here, in the same transaction that updates the hash
-- index, and every sink delivers them on its own from its offset. recipients lists the sinks a batch is for as
-- |sink id|sink id|: fully loaded sinks get changed rows, sinks being loaded get every row.
CREATE TABLE outbox_batch (
  job_id TEXT NOT NULL,
  batch_id BIGINT NOT NULL,
  recipients TEXT NOT NULL,
  change_count INT NOT NULL,
  payload TEXT NOT NULL,               -- the batch as JSON with its target schema (see Batches)
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (job_id, batch_id)
);

-- Each sink's progress through its job's outbox, with its retry state.
CREATE TABLE sink_offset (
  job_id TEXT NOT NULL,
  sink_id TEXT NOT NULL,
  last_acked_batch BIGINT NOT NULL,
  status TEXT NOT NULL,                -- ACTIVE | PAUSED | RESNAPSHOT_REQUIRED
  attempts INT NOT NULL DEFAULT 0,     -- failed attempts at the next batch
  next_retry_at TIMESTAMP,
  last_error TEXT,
  delivered_at TIMESTAMP,
  PRIMARY KEY (job_id, sink_id)
);
