-- Only the holder of an unexpired lease fires scheduled runs, so two servers sharing a state store
-- never run the same job twice. Single node in v1: the one server simply keeps renewing it.
CREATE TABLE scheduler_lease (
  name TEXT PRIMARY KEY,               -- 'scheduler'
  holder TEXT NOT NULL,
  expires_at TIMESTAMP NOT NULL
);
