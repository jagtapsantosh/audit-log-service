# Scenario B — Retention, Redaction, Bulk Export

Extends Scenario A. Three problems: archive old records without false verify failures; redact sensitive payload fields without breaking the hash chain; export a self-contained verifiable bundle.

---

## B1. Retention

### Requirement

Records older than a configurable window must be archivable / soft-deletable. `GET /audit/verify` must not report a break solely because records were archived per policy.

### Design: soft archive

Config: `audit.retention.days` (default **365**). A scheduled job (and `POST /audit/admin/archive`) sets `status = 'ARCHIVED'` and `archived_at` where **`recorded_at`** is older than the window. Ingest time is used so a backdated `occurredAt` cannot keep a row hot. Rows are **never deleted**.

**Auth for `POST /audit/admin/archive`:** JWT with `audit.admin` only (no API key).

- Default `GET /audit/events` excludes `ARCHIVED` unless `includeArchived=true`.
- Verify walks **all** rows (`ACTIVE` + `ARCHIVED`) by `sequence_num`. Archived links stay in the chain.

Flyway `V2__retention_and_redaction.sql`: `status`, `archived_at`, `has_redactions` on `audit_records`, plus table `audit_redactions`. Archive only updates `status` / `archived_at` — never hashed payload, hashes, or clocks. The append-only trigger must allow that for the app role while still blocking hashed-column UPDATE/DELETE.

### Trade-offs

| Option | Tamper evidence | Privacy / storage | Verdict |
|--------|-----------------|-------------------|---------|
| Soft archive (chosen) | Chain intact; verify has no false positives | Table grows forever | Chosen for correctness |
| Hard delete | Gap or missing predecessor → false or real break | Reclaims space | Rejected |
| Delete + tombstone | Tombstone can preserve hashes if designed carefully | Extra protocol; still stores a row | Out of scope for the timebox |
| Separate archive table | Verify must union two stores | Operational complexity | Rejected |

Limitation: storage grows with all historical events. Production would add partition/tiering; not in this prototype.

---

## B2. Structured redaction

### The engineering problem

`contentHash` covers the original payload. Replacing `accountNumber` in place changes the canonical bytes and breaks that record and every successor.

Privacy still requires that query/export not return the sensitive value.

### Chosen design: immutable hash + overlay

Original payload and hashes stay as written. Redaction is a **separate row**; read APIs apply a view.

```
audit_redactions (
  id, audit_record_id, field_path,   -- e.g. "accountNumber" or "nested.ssn"
  redacted_at, redacted_by, reason
)
```

`POST /audit/events/{id}/redact`

```json
{ "fieldPaths": ["accountNumber"], "reason": "GDPR", "redactedBy": "admin-1" }
```

**Auth:** JWT with `audit.admin` only (no API key). `redactedBy` should match the JWT `sub` (or be derived from it) so callers cannot spoof the operator id.

- Query/export replace values at `fieldPath` with `"[REDACTED]"` and list `redactedFields`.
- Verify uses stored original payload → chain still intact.
- `has_redactions` on the record is a cache flag.

`field_path` is a simple dotted path into the JSON object (no array indexes in v1). Unknown paths → 400.

### Alternatives considered

| Approach | Why rejected |
|----------|----------------|
| Mutate payload and recompute hash | Destroys evidence of the original value; looks like tamper |
| Hash a redaction-exempt subset of fields | Attackers can change exempt fields undetected |
| Encrypt sensitive fields in payload | Key management out of scope; still need a reveal path |
| Store plaintext sidecar and hashed ciphertext in-row | Two sources of truth; verify becomes ambiguous |

### Limitations (own them)

- Operators with DB access can still read original JSONB. Overlay protects **API consumers**, not DBAs. Production would add column encryption or an HSM.
- Redaction is field-level, not record-level delete.
- Nested arrays are not addressed in v1.

---

## B3. Bulk export

`GET /audit/export?actorId=` **or** `?resourceId=` (optional `resourceType`). Require one of actor/resource. Apply redaction view. Download JSON.

**Auth:** API key or JWT with `audit.read`.

```json
{
  "exportVersion": "1.0",
  "exportedAt": "2026-08-16T12:00:00Z",
  "filter": { "actorId": "user-123" },
  "genesisHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "records": [
    {
      "sequence": 1,
      "eventType": "USER_LOGIN",
      "actorId": "user-123",
      "resourceType": "SESSION",
      "resourceId": "sess-abc",
      "occurredAt": "2026-08-14T11:30:00Z",
      "recordedAt": "2026-08-14T11:37:00Z",
      "contentHash": "...",
      "previousHash": "...",
      "payload": { "accountNumber": "[REDACTED]" },
      "redactedFields": ["accountNumber"]
    }
  ],
  "bundleHash": "<SHA-256 of canonical exportVersion+exportedAt+filter+genesisHash+records>"
}
```

**Export is a subsequence, not a mini-chain.** `GET /audit/export?actorId=` (or resource) is a **sparse slice** of the **global** chain. `previousHash` often points at a record **not** in the file. Sequence gaps are expected. The PDF asks that the bundle prove records **have not been altered since export**, not that a recipient can replay `/audit/verify`.

`ExportVerifier` (standalone Java + README algorithm):

1. Recompute `bundleHash` over canonical `exportVersion` + `exportedAt` + `filter` + `genesisHash` + `records`. Mismatch → tampered since export.
2. Per-record `contentHash` / `previousHash` are copies of server values.
3. Recompute `contentHash` **only** when `redactedFields` is empty (payload is unredacted).
4. Do **not** fail because sequence numbers have gaps.

Full chain integrity remains `GET /audit/verify` on the service (plaintext still in the DB).

Document this split in README:

- `bundleHash` → bundle not altered since export.
- Redacted payloads cannot recompute per-record hashes; unredacted records can.

---

## Decomposition

| # | Task | Depends on | Acceptance |
|---|------|------------|------------|
| B1 | V2 migration: status, archived_at, has_redactions, `audit_redactions` | A schema | Flyway up on existing A DB |
| B2 | `RetentionService` + admin/cron | B1 | Records past window become ARCHIVED; verify still intact; archive endpoint JWT `audit.admin` only |
| B3 | Query `includeArchived` | B2 | Default hides archived |
| B4 | `RedactionService` + POST redact | B1 | Query shows `[REDACTED]`; verify intact; SQL tamper still detected; redact is JWT `audit.admin` |
| B5 | Export + `ExportVerifier` | B4 | Bundle downloads; `bundleHash` matches; gaps in `sequence` do not fail; unredacted records re-hash; redacted ones skip content re-hash |

---

## Validation

- Archive sweep; verify `intact: true` with mixed ACTIVE/ARCHIVED.
- API key on redact or archive → 403.
- Redact `accountNumber`; GET event shows `[REDACTED]`; verify intact.
- SQL `UPDATE` of original payload still yields `CONTENT_HASH_MISMATCH`.
- Export for an actor (sparse sequences); `ExportVerifier` accepts despite gaps; after editing the file, `bundleHash` fails.
