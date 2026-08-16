-- Gap closure: detect tail deletion, and make ingest retries safe.
--
-- audit_chain_head is a single-row pointer to the newest (sequence, content_hash) the service
-- itself published. GET /audit/verify compares it to the current table max. Deleting the newest
-- audit_records rows without also rewriting this row is TAIL_TRUNCATION.
--
-- This is still not WORM: a DBA who updates both tables can hide truncation. Production should
-- copy each published head to a sink the database owner does not control.

CREATE TABLE audit_chain_head (
  id            SMALLINT PRIMARY KEY CHECK (id = 1),
  sequence_num  BIGINT NOT NULL,
  content_hash  CHAR(64) NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL
);

-- Replay table. Not part of the hash chain. Unique per caller + key.
CREATE TABLE audit_idempotency_keys (
  id              BIGSERIAL PRIMARY KEY,
  client_id       VARCHAR(255) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash    CHAR(64) NOT NULL,
  audit_record_id BIGINT NOT NULL REFERENCES audit_records(id),
  created_at      TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_idempotency_client_key UNIQUE (client_id, idempotency_key)
);

CREATE INDEX idx_idempotency_record ON audit_idempotency_keys(audit_record_id);
