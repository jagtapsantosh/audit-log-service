-- Scenario B: retention (soft archive) and structured redaction (overlay).
--
-- Nothing here touches a hashed column. status/archived_at/has_redactions are metadata outside the
-- hash pre-image, and redactions live in their own table, so the chain stays verifiable after both
-- an archive sweep and a redaction.

ALTER TABLE audit_records ADD COLUMN status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE audit_records ADD COLUMN archived_at    TIMESTAMPTZ;
ALTER TABLE audit_records ADD COLUMN has_redactions BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE audit_records ADD CONSTRAINT chk_audit_status CHECK (status IN ('ACTIVE', 'ARCHIVED'));

-- Overlay rows. The original payload is never rewritten; read APIs mask these paths on the way out.
CREATE TABLE audit_redactions (
  id              BIGSERIAL PRIMARY KEY,
  audit_record_id BIGINT NOT NULL REFERENCES audit_records(id),
  field_path      VARCHAR(255) NOT NULL,
  redacted_at     TIMESTAMPTZ NOT NULL,
  redacted_by     VARCHAR(255) NOT NULL,
  reason          VARCHAR(500),
  -- Redacting the same path twice is a no-op rather than a second row.
  CONSTRAINT uq_redaction_record_path UNIQUE (audit_record_id, field_path)
);

CREATE INDEX idx_audit_status ON audit_records(status);
CREATE INDEX idx_redaction_record ON audit_redactions(audit_record_id);
