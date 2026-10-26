-- Sinks that have received a full load. A sink missing here (just added, or its target changed)
-- gets every source row on the job's next run, not only the changed ones.
CREATE TABLE sink_load (
  job_id TEXT NOT NULL,
  sink_id TEXT NOT NULL,               -- type:connection:target
  loaded_at TIMESTAMP NOT NULL,
  PRIMARY KEY (job_id, sink_id)
);
