-- Server settings (M7): alert channels and rules, retention. One JSON document per key.
CREATE TABLE setting (
  key TEXT PRIMARY KEY,
  value_json TEXT NOT NULL,
  updated_at TIMESTAMP
);
