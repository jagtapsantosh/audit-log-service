# Scenario A — Core Audit Log Service

Greenfield: write, query, and verify a global SHA-256 hash chain. The assignment is validated through these APIs — write, query, verify, then tamper in SQL and verify again.

Design details: [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Requirement interpretation

| Spec item | Engineering meaning |
|-----------|---------------------|
| Write API | Accept `eventType`, `actorId`, `resourceType`, `resourceId`, `occurredAt`, `payload`; assign `recordedAt` on the server |
| Append-only | No PUT/PATCH/DELETE for event content |
| Query API | Filter any combination of actor, resource, event type, time range (`from`/`to` on `occurredAt`); paginate |
| Hash chain | Each row stores `content_hash` and `previous_hash`; hash includes both clocks |
| `GET /audit/verify` | Intact vs first broken record and violation type |

**Timestamp choice:** dual clocks. The PDF allows caller-supplied **or** server-assigned; we keep **both**. Client `occurredAt` is when the business event happened (async/offline). Server `recordedAt` is ingest time. Both are hashed. Query `from`/`to` uses `occurredAt`. Documented in OpenAPI.

---

## Decomposition

Dependencies run top to bottom. Do not start a task until its predecessors exist.

| # | Task | Intent | Constraints | Acceptance |
|---|------|--------|-------------|------------|
| A1 | Bootstrap | Runnable Spring Boot 3 / Java 21 app | Gradle, Docker Compose Postgres 16, Flyway, actuator health | `docker compose up -d` + `./gradlew bootRun`; `/actuator/health` is UP |
| A2 | Schema + entity | Persist events | Flyway `V1`; unique `sequence_num`; JSONB payload; `occurred_at` + `recorded_at` | Migration applies on empty DB; entity maps all V1 columns |
| A3 | `HashChainService` | Deterministic SHA-256 | Sorted-key canonical JSON including both clocks; genesis = 64 zero hex chars | Golden unit tests: known input → known hash; first record uses genesis |
| A4 | `AuditWriteService` | Append one chain link | One transaction; advisory lock; no client `id`/`sequence`/`hash`/`recordedAt` | POST returns `id`, `sequence`, `contentHash`, `occurredAt`, `recordedAt`; sequence is n+1 |
| A5 | `AuditQueryService` | Filter + page | All filters optional and combinable; max page size 200 | Integration tests for each param alone and together; stable `sequence_num` order |
| A6 | `AuditVerifyService` | Detect tamper | Walk **all** rows by sequence; stop at first violation | Intact after honest writes; SQL payload edit → `CONTENT_HASH_MISMATCH` at that sequence |
| A7 | Controllers + errors | HTTP contract | `@Valid` DTOs; no update/delete mappings | OpenAPI at `/swagger-ui.html` with bearer + apiKey schemes; error envelope `{error, code, timestamp}` |
| A8 | Hybrid auth | Who may call write/query/verify | API key and/or JWT; verify is JWT-only | 401 without credentials; 403 if scope missing; OpenAPI documents both schemes |

---

## Write contract

`POST /audit/events`

```json
{
  "eventType": "USER_LOGIN",
  "actorId": "user-123",
  "resourceType": "SESSION",
  "resourceId": "sess-abc",
  "occurredAt": "2026-08-14T11:30:00Z",
  "payload": { "ip": "10.0.0.1" }
}
```

Rejected if required strings are blank, `occurredAt` is missing, payload exceeds 64KB, or `occurredAt` is more than 5 minutes after server `recordedAt`. Arbitrarily old `occurredAt` is allowed. Response includes `occurredAt`, `recordedAt`, and hashes. Implementation: lock → load head → `sequence = head+1`, `previousHash = head.contentHash or GENESIS`, `recordedAt = Instant.now()` → hash → insert.

**Auth:** `X-API-Key` (primary, scope `audit.write`) or `Authorization: Bearer` with `audit.write`. Missing/invalid credentials → 401.

---

## Query contract

`GET /audit/events`

Query params: `actorId`, `resourceType`, `resourceId`, `eventType`, `from`, `to`, optional `recordedFrom`/`recordedTo`, `page`, `size`. `from`/`to` are inclusive on **`occurredAt`**. `recordedFrom`/`recordedTo` are inclusive on **`recordedAt`**. Default size 50.

**Auth:** API key or JWT with `audit.read`. 401 without credentials.

---

## Verify contract

`GET /audit/verify`

```json
{
  "intact": false,
  "totalRecords": 42,
  "firstViolation": {
    "sequence": 7,
    "recordId": 7,
    "violationType": "CONTENT_HASH_MISMATCH",
    "expectedHash": "...",
    "actualHash": "..."
  }
}
```

`firstViolation` is omitted when `intact` is true. Types: `CONTENT_HASH_MISMATCH`, `PREVIOUS_HASH_BREAK`, `SEQUENCE_GAP`.

**Auth:** JWT with `audit.read` only (no API key). A leaked ingest key must not probe chain integrity.

---

## Validation

| Test | Type | Proves |
|------|------|--------|
| Known payload → known hash; genesis on sequence 1 | Unit | Canonicalization is stable |
| `occurredAt` more than 5 minutes after persist | Integration | Write rejected |
| POST 3 events; verify intact | Integration | Happy-path chain |
| 10 parallel POSTs; verify intact; sequences unique and contiguous | Integration | Lock prevents split-brain `previousHash` |
| Each filter alone and combined | Integration | Query spec |
| Page through results | Integration | Order by `sequence_num` |
| `UPDATE` payload in DB; verify reports break at that sequence | Integration | Tamper evidence |
| No credentials on write/query/verify | Integration | 401 |
| API key on verify | Integration | 403 |
| JWT without `audit.write` on POST | Integration | 403 |

**Manual flow** (also in README after bootstrap):

1. POST several events
2. GET with filters
3. GET `/audit/verify` → `intact: true`
4. `UPDATE audit_records SET payload = '{"tampered":true}' WHERE sequence_num = 2;`
5. GET `/audit/verify` → broken, sequence 2, `CONTENT_HASH_MISMATCH`

---

## Out of scope for A

Retention, redaction, export, compliance reports (B and C). Corporate IdP/JWKS (local `POST /auth/token` only). Physical row delete.
