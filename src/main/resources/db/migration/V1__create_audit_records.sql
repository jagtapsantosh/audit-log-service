CREATE TABLE audit_records (
  id            BIGSERIAL PRIMARY KEY,
  sequence_num  BIGINT NOT NULL UNIQUE,
  event_type    VARCHAR(100) NOT NULL,
  actor_id      VARCHAR(255) NOT NULL,
  resource_type VARCHAR(100) NOT NULL,
  resource_id   VARCHAR(255) NOT NULL,
  payload       JSONB NOT NULL DEFAULT '{}',
  timestamp     TIMESTAMPTZ NOT NULL,
  content_hash  CHAR(64) NOT NULL,
  previous_hash CHAR(64) NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_actor ON audit_records(actor_id);
CREATE INDEX idx_audit_resource ON audit_records(resource_type, resource_id);
CREATE INDEX idx_audit_event_type ON audit_records(event_type);
CREATE INDEX idx_audit_timestamp ON audit_records(timestamp);
