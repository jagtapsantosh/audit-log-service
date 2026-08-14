# Architecture Overview

Tamper-evident audit log service. Records are append-only. Any change to a stored record is detectable by walking a SHA-256 hash chain.

Stack: **Java 21**, **Spring Boot 3.5**, **Gradle**, **PostgreSQL 16**, Flyway, JUnit 5, Testcontainers, SpringDoc OpenAPI.

Status: bootstrap complete. Remaining Scenario A APIs follow `IMPLEMENTATION_PLAN.md`.

---

## Components

![High-level architecture](architecture.svg)

| Layer | Component | Responsibility |
|-------|-----------|----------------|
| API | `AuditWriteController` | `POST /audit/events` — validate and append |
| API | `AuditQueryController` | `GET /audit/events` — filter, paginate, apply redaction view |
| API | `AuditVerifyController` | `GET /audit/verify` — walk full chain |
| API | `AuditExportController` | `GET /audit/export` — verifiable bundle |
| API | `ComplianceReportController` | `GET /audit/compliance/access-report` (+ export) |
| Domain | `HashChainService` | Canonical JSON, SHA-256, genesis, recompute on verify |
| Domain | `AuditWriteService` | Sequence assignment, advisory lock, persist |
| Domain | `AuditQueryService` | Dynamic filters + pagination |
| Domain | `AuditVerifyService` | Ordered walk, first-violation report |
| Domain | `RetentionService` | Soft-archive past `audit.retention.days` |
| Domain | `RedactionService` | Overlay only; never mutate hashed fields |
| Domain | `ExportBundleService` | Self-contained JSON bundle + `bundleHash` |
| Domain | `ExportVerifier` | Standalone recipient-side bundle check |
| Domain | `ComplianceReportService` | Point-in-time access report + chain head hash |
| Persistence | `AuditRecordRepository` | Insert + query only; no update/delete of event fields |
| Persistence | PostgreSQL | `JSONB` payload, unique `sequence_num` |

Package root: `com.auditlog` (`api`, `domain`, `persistence`, `config`).

---

## Data Model

### `audit_records` (Flyway `V1__create_audit_records.sql`)

| Column | Type | Notes |
|--------|------|--------|
| `id` | `BIGSERIAL` | Surrogate PK |
| `sequence_num` | `BIGINT UNIQUE NOT NULL` | Chain order; monotonic |
| `event_type` | `VARCHAR(100) NOT NULL` | e.g. `USER_LOGIN` |
| `actor_id` | `VARCHAR(255) NOT NULL` | Who caused the event |
| `resource_type` | `VARCHAR(100) NOT NULL` | e.g. `SESSION`, `CLIENT_ACCOUNT` |
| `resource_id` | `VARCHAR(255) NOT NULL` | Specific resource |
| `payload` | `JSONB NOT NULL DEFAULT '{}'` | Event detail; original values always stored |
| `timestamp` | `TIMESTAMPTZ NOT NULL` | **Server-assigned** at persist |
| `content_hash` | `CHAR(64) NOT NULL` | SHA-256 hex of canonical content |
| `previous_hash` | `CHAR(64) NOT NULL` | Predecessor `content_hash`, or genesis |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | Insert time (not hashed) |

Indexes: `actor_id`; `(resource_type, resource_id)`; `event_type`; `timestamp`.

### Scenario B extensions (`V2__retention_and_redaction.sql`)

`audit_records` adds `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'`, `archived_at TIMESTAMPTZ`, `has_redactions BOOLEAN NOT NULL DEFAULT FALSE`.

`audit_redactions`: `id`, `audit_record_id`, `field_path`, `redacted_at`, `redacted_by`, `reason`. Overlay only — payload bytes used for hashing are never rewritten.

---

## Hash Chain Design

### Choices

| Decision | Choice | Why |
|----------|--------|-----|
| Timestamp | Server `Instant` at persist | Caller cannot backdate |
| Algorithm | SHA-256 via `MessageDigest` | Standard, no extra crypto deps |
| Chain scope | One global sequence | Simplest verify/export; every record links to the previous |
| Genesis | 64 hex zeros | Explicit first-link; no special-case hash |
| Canonical form | Sorted keys, UTF-8, no whitespace | Same bytes on write and verify |
| Append-only | No update/delete APIs or repo methods | Tamper only via direct SQL (the assigned test) |

Rejected: per-resource sub-chains (harder verify/export); SHA-512 (no extra security needed here); caller-supplied timestamp (backdating).

### Canonical object

Hash input is JSON with **lexicographically sorted keys** (nested `payload` sorted recursively), UTF-8, no insignificant whitespace. Field names are API names, not DB columns:

```json
{
  "actorId": "user-123",
  "eventType": "USER_LOGIN",
  "payload": { "ip": "10.0.0.1" },
  "previousHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "resourceId": "sess-abc",
  "resourceType": "SESSION",
  "sequence": 1,
  "timestamp": "2026-08-14T11:37:00Z"
}
```

`timestamp` is ISO-8601 UTC from `Instant.toString()`. `sequence` is a JSON number. `created_at`, `status`, and redaction rows are **not** in the hash.

```
contentHash(n)  = SHA-256(canonical(record n including previousHash))
previousHash(1) = GENESIS
previousHash(n) = contentHash(n-1)   for n > 1
```

### Verification

Walk `ORDER BY sequence_num ASC` (ACTIVE and ARCHIVED). For each row: recompute `contentHash`; compare to stored; check `previousHash` equals predecessor `contentHash` (or genesis for sequence 1); check no sequence gaps. Stop at the first of: `CONTENT_HASH_MISMATCH`, `PREVIOUS_HASH_BREAK`, `SEQUENCE_GAP`.

Redaction does not change this walk: verify always uses stored original payload.

### Concurrent append

`pg_advisory_xact_lock` (or `SELECT ... FOR UPDATE` on a chain-head row) inside the write transaction so two writers cannot share the same `previousHash`.

---

## API Surface

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/audit/events` | Append |
| `GET` | `/audit/events` | Filter + paginate |
| `GET` | `/audit/verify` | Chain integrity |
| `POST` | `/audit/events/{id}/redact` | Overlay redaction |
| `POST` | `/audit/admin/archive` | Retention sweep |
| `GET` | `/audit/export` | Verifiable bundle |
| `GET` | `/audit/compliance/access-report` | Access report |
| `GET` | `/audit/compliance/access-report/export` | CSV/JSON download |

No update or delete of audit event content. Query defaults: page size 50, max 200, order `sequence_num ASC`. Payload max 64KB.

Errors: `{ "error", "code", "timestamp" }` via `@ControllerAdvice`.

---

## Cross-Cutting

- Validation: `@NotBlank` on required write fields; payload size cap.
- SQL: JPA parameterized queries only.
- Auth: optional API key on admin/redact in a later pass; rate limiting is a production follow-up.
- Logs: append, verify result, archive count.
- Metrics: `audit.events.written`, `audit.verify.duration`, `audit.chain.intact`.
