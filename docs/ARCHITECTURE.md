# Architecture Overview

Tamper-evident audit log service. Records are append-only. Any change to a stored record is detectable by walking a SHA-256 hash chain.

Stack: **Java 21**, **Spring Boot 3.5**, **Gradle**, **PostgreSQL 16**, Flyway, JUnit 5, Testcontainers, SpringDoc OpenAPI.

Status: Scenario A implemented (write, query, verify, hybrid auth, tamper tests). Scenarios B and C follow `IMPLEMENTATION_PLAN.md`.

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
| API | `TokenController` | Prototype only: `POST /auth/token` (client credentials JWT) |
| Config | `ApiKeyAuthenticationFilter` | `X-API-Key` → hashed lookup → Spring authorities |
| Config | JWT resource server | `Authorization: Bearer` validation (HMAC locally; JWKS in production) |
| Domain | `CanonicalJson` | Deterministic hash pre-image (sorted keys, normalized numbers, clock precision) |
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
| Persistence | `JpaAuditRecordStore` | Implements the domain's `AuditRecordStore` port; entity ↔ domain mapping |
| Persistence | PostgreSQL | `JSONB` payload, unique `sequence_num` |

Package root: `com.auditlog` (`api`, `domain`, `persistence`, `config`). The domain layer depends on
the `AuditRecordStore` **port** rather than on JPA entities, so hashing and chain verification can be
unit-tested without a database and contain no persistence concerns.

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
| `occurred_at` | `TIMESTAMPTZ NOT NULL` | Client: when the business event happened (hashed) |
| `recorded_at` | `TIMESTAMPTZ NOT NULL` | Server: when this service accepted the row (hashed) |
| `content_hash` | `CHAR(64) NOT NULL` | SHA-256 hex of canonical content |
| `previous_hash` | `CHAR(64) NOT NULL` | Predecessor `content_hash`, or genesis |

Indexes: `actor_id`; `(resource_type, resource_id)`; `event_type`; `occurred_at`; `recorded_at`.

### Scenario B extensions (`V2__retention_and_redaction.sql`)

`audit_records` adds `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'`, `archived_at TIMESTAMPTZ`, `has_redactions BOOLEAN NOT NULL DEFAULT FALSE`.

`audit_redactions`: `id`, `audit_record_id`, `field_path`, `redacted_at`, `redacted_by`, `reason`. Overlay only — payload bytes used for hashing are never rewritten.

---

## Hash Chain Design

### Choices

| Decision | Choice | Why |
|----------|--------|-----|
| Clocks | Dual: client `occurredAt` + server `recordedAt`, both hashed | PDF allows either; both preserve event time and ingest time |
| Algorithm | SHA-256 via `MessageDigest` | Standard, no extra crypto deps |
| Chain scope | One global sequence | Simplest verify/export; every record links to the previous |
| Genesis | 64 hex zeros | Explicit first-link; no special-case hash |
| Canonical form | Sorted keys, UTF-8, no whitespace, normalized numbers | Same bytes on write and verify |
| Append-only | No update/delete APIs or repo methods; entity columns are `updatable = false` | Tamper only via direct SQL (the assigned test) |

Rejected: per-resource sub-chains (harder verify/export); SHA-512 (no extra security needed here); **caller-only** timestamp (no ingest evidence); **server-only** timestamp (loses delayed/offline event time); a third unhashed `created_at` (redundant with `recordedAt`).

**Which clock for what**

| Use | Clock |
|-----|--------|
| Write body | Client sends `occurredAt` (required). Server sets `recordedAt` |
| Validation | Reject if `occurredAt` > `recordedAt` + 5 minutes; old `occurredAt` allowed |
| `GET /audit/events` `from`/`to` | `occurredAt` |
| Optional `recordedFrom`/`recordedTo` | `recordedAt` |
| Retention/archive | `recordedAt` (backdated `occurredAt` cannot stay hot) |
| Compliance report `from`/`to` | `occurredAt` |
| Error envelope `timestamp` | When the error was produced (not an audit clock) |

### Canonical object

Hash input is JSON with **lexicographically sorted keys** (nested `payload` sorted recursively), UTF-8, no insignificant whitespace. Field names are API names, not DB columns:

```json
{
  "actorId": "user-123",
  "eventType": "USER_LOGIN",
  "occurredAt": "2026-08-14T11:30:00Z",
  "payload": { "ip": "10.0.0.1" },
  "previousHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "recordedAt": "2026-08-14T11:37:00Z",
  "resourceId": "sess-abc",
  "resourceType": "SESSION",
  "sequence": 1
}
```

`occurredAt` and `recordedAt` are ISO-8601 UTC from `Instant.toString()`. `sequence` is a JSON number. `status` and redaction rows are **not** in the hash. Tampering with either clock after persist still breaks the chain.

**Two round-trip hazards the canonical form has to survive** (both covered by unit tests, `CanonicalJson`):

1. **`jsonb` re-renders numbers.** PostgreSQL stores `1e2` and returns `100`, and keeps the scale of `1.50`. Numbers are therefore canonicalized to plain decimal with trailing zeros stripped, so `1`, `1.0`, and `1e0` all hash as `1`. Without this, an honest record fails verification after a database round trip.
2. **`timestamptz` keeps microseconds.** Nanosecond input would be silently truncated by the database and re-hash differently, so both clocks are truncated to microseconds *before* hashing.

Payload key order is not a hazard because the canonical form sorts keys recursively — which is also why storing the payload as `jsonb` (which discards key order) is safe.

```
contentHash(n)  = SHA-256(canonical(record n including previousHash))
previousHash(1) = GENESIS
previousHash(n) = contentHash(n-1)   for n > 1
```

### Verification

Walk `ORDER BY sequence_num ASC` (ACTIVE and ARCHIVED). For each row: recompute `contentHash`; compare to stored; check `previousHash` equals predecessor `contentHash` (or genesis for sequence 1); check no sequence gaps. Stop at the first of: `SEQUENCE_GAP`, `PREVIOUS_HASH_BREAK`, `CONTENT_HASH_MISMATCH` (checked in that order per record). The response adds a human-readable `detail` alongside the hashes.

Links compare against the predecessor's **stored** `contentHash`, so one tampered record is reported once instead of cascading. An empty chain is intact. The walk is keyset-paged (500 rows) so memory is bounded; it is O(n) with no checkpoints, since a checkpoint would itself need to be trusted.

Redaction does not change this walk: verify always uses stored original payload.

### Concurrent append

`pg_advisory_xact_lock` inside the write transaction, taken **before** the chain head is read, so two writers cannot share the same `previousHash`. Released on commit or rollback. Proven by `ConcurrentAppendIT` (10 parallel writers over real HTTP).

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
| `POST` | `/auth/token` | Prototype JWT mint (client credentials) |

No update or delete of audit event content: those verbs are unmapped (**405**), the repository interface declares no update/delete method, and every entity column is `updatable = false`. Query defaults: page size 50, max 200, order `sequence_num ASC`. Payload max 64KB and must be a JSON object. Unknown request fields are rejected so a caller cannot believe it supplied a sequence number, ingest time, or hash.

Errors: `{ "error", "code", "timestamp" }` via `@ControllerAdvice` (including 401/403; no key-enumeration messages). A broken chain is still `200` with `intact: false` — the report succeeded.

**Prevention vs detection.** Enforcement above is application-level; a DBA with SQL access can still rewrite rows, which is precisely what verification exposes. A database trigger that rejects writes to hashed columns for the *application* role is designed in [SCENARIO_B.md](SCENARIO_B.md) and deferred to Scenario B, because it requires splitting the app role from the owner role the assignment's tamper step uses.

### API security (hybrid)

Rejected: unauthenticated APIs; optional API key “later”; API keys on admin/compliance (a leaked ingest key must not redact, archive, or probe verify).

**API keys** — service-to-service ingest and optional batch read. Header `X-API-Key`. Store SHA-256(key + pepper) in `audit.security.api-keys` (config). Each key: `clientId` + scopes.

**JWT** — ops and compliance. Header `Authorization: Bearer`. Prototype `POST /auth/token` issues ~15 min HMAC JWTs. Production: corporate IdP + JWKS; drop the local token endpoint.

| Scope | Meaning |
|-------|---------|
| `audit.write` | Append |
| `audit.read` | Query, export, verify |
| `audit.admin` | Redact, archive (implies read) |
| `audit.compliance` | Compliance APIs |

| Path | API key | JWT | Scope |
|------|---------|-----|-------|
| `POST /audit/events` | yes (primary) | yes | `audit.write` |
| `GET /audit/events` | yes | yes | `audit.read` |
| `GET /audit/export` | yes | yes | `audit.read` |
| `GET /audit/verify` | no | yes | `audit.read` |
| `POST /audit/events/{id}/redact` | no | yes | `audit.admin` |
| `POST /audit/admin/archive` | no | yes | `audit.admin` |
| `GET /audit/compliance/*` | no | yes | `audit.compliance` |
| `POST /auth/token` | — | — | Public, rate-limited |
| `/actuator/health` | — | — | Public |
| OpenAPI | — | — | Local only; deny in `prod` |

TLS at the reverse proxy in production. Rate limit per IP on `/auth/token` (~10/min). Never log `Authorization` or `X-API-Key`. Filters and `POST /auth/token` are implemented.

---

## Cross-Cutting

- Validation: `@NotBlank` on required write fields; required `occurredAt`; payload size cap; reject `occurredAt` more than 5 minutes after `recordedAt`.
- SQL: JPA parameterized queries only.
- Auth: hybrid API key + JWT (see [API security](#api-security-hybrid)); implemented. Filters and `POST /auth/token` are live.
- Logs: append, verify result, archive count; never log credentials.
- Metrics: `audit.events.written`, `audit.verify.duration`, `audit.chain.intact`.
