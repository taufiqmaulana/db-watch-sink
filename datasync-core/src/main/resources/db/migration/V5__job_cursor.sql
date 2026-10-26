-- The highest watermark value delivered by a WATERMARK or HYBRID job (design 5.1), stored as its canonical
-- string. A cursor for another column than the job's current one is ignored, so the next run reads everything.
CREATE TABLE job_cursor (
  job_id TEXT PRIMARY KEY,
  watermark_column TEXT NOT NULL,
  watermark_value TEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
