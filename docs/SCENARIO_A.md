# Scenario A — Core Audit Log Service

Greenfield: write, query, and verify a global SHA-256 hash chain. The assignment is validated through these APIs — write, query, verify, then tamper in SQL and verify again.

**Status: implemented.** All tasks below are done and covered by tests; the runnable script is in the [README](../README.md#scenario-a-validation-script). Design details: [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Requirement interpretation

| Spec item | Engineering meaning |
|-----------|---------------------|
| Write API | Accept `eventType`, `actorId`, `resourceType`, `resourceId`, `occurredAt`, `payload`; assign `recordedAt` on the server. The spec's field name `timestamp` is accepted as an alias for `occurredAt` |
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
| A3 | `HashChainService` + `CanonicalJson` | Deterministic SHA-256 | Canonical JSON: recursively sorted keys, no pretty print, `Instant.toString()` truncated to microseconds, numbers normalized (trailing zeros stripped), payload as `JsonNode` (not `Map`/`Double`), missing payload `{}`; genesis = 64 zero hex chars | Golden unit tests: known record → known hex; first record uses genesis |
| A4 | `AuditWriteService` | Append one chain link | One transaction; advisory lock; no client `id`/`sequence`/`hash`/`recordedAt` | POST returns `id`, `sequence`, `contentHash`, `occurredAt`, `recordedAt`; sequence is n+1 |
| A5 | `AuditQueryService` | Filter + page | All filters optional and combinable; max page size 200 | Integration tests for each param alone and together; stable `sequence_num` order |
| A6 | `AuditVerifyService` | Detect tamper | Walk **all** rows by sequence; stop at first violation; empty chain is intact | Intact after honest writes; empty DB → `intact: true`, `totalRecords: 0`; SQL payload edit → `CONTENT_HASH_MISMATCH` at that sequence |
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

Unknown request fields are rejected (**400** `MALFORMED_REQUEST`), so a caller cannot believe it set `sequence`, `recordedAt`, or a hash. Payload must be a JSON object.

**Canonical JSON (hash input):** recursively sorted keys, no pretty print, `Instant.toString()` truncated to **microseconds** (PostgreSQL `timestamptz` precision), numbers normalized to plain decimal with trailing zeros stripped (so `jsonb` re-rendering `1e2` as `100` cannot break an honest record), payload as `JsonNode`, missing payload → `{}`. Golden test: known record → known hex, with the pre-image asserted too.

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

The response also carries a human-readable `detail`. `expectedHash` is what the stored fields hash to now; `actualHash` is the hash committed at write time. Empty chain (JWT, zero rows): `{ "intact": true, "totalRecords": 0 }`. A broken chain is still HTTP 200 — the integrity report succeeded.

**Detection boundary.** A chain walk cannot detect deletion of the **newest** record(s): the remaining prefix is still internally consistent, so verify answers `intact: true` with a lower `totalRecords`. Interior deletes (`SEQUENCE_GAP`), field edits including both clocks (`CONTENT_HASH_MISMATCH`), and rewritten links (`PREVIOUS_HASH_BREAK`) are all caught. Detecting truncation requires a head anchor stored outside this table, which is out of scope for A — see Limitations in [ENGINEERING_SUMMARY.md](ENGINEERING_SUMMARY.md).

**Auth:** JWT with `audit.read` only (no API key). A leaked ingest key must not probe chain integrity.

**Append-only enforcement (implemented):** no update/delete mappings (`PUT`/`DELETE` → **405**), the repository interface extends bare `Repository` so Spring Data exposes no delete, and every entity column is `updatable = false`. This is application-level prevention; SQL-level tamper remains possible by design, which is what verify detects.

**Append-only vs tamper demo (deferred to B):** a Flyway trigger rejecting UPDATE/DELETE of hashed columns must apply to the **app role** only, because `docker-compose` currently runs the app and the assignment's `psql` tamper as the same `auditlog` role. Splitting those roles lands with Scenario B; adding the trigger now would block the required validation step.

---

## Validation

All of the following are implemented and passing (66 tests, no skips with Docker running).

| Test | Type | Proves |
|------|------|--------|
| Known payload → known hash and known pre-image; genesis on sequence 1 | Unit | Canonicalization is stable (golden hex computed outside the codebase) |
| Every hashed field changes the hash, including both clocks | Unit | Nothing hashed is silently ignored |
| Number spellings and nanosecond precision normalize | Unit | A `jsonb` / `timestamptz` round trip cannot fake a tamper |
| Lock is acquired before the chain head is read | Unit (Mockito) | The concurrency guard is in the right place, not just present |
| Empty, intact, and all three violation types | Unit (Mockito) | Verify reports the first violation only |
| Empty DB; JWT GET `/audit/verify` | Integration | `intact: true`, `totalRecords: 0` |
| `occurredAt` far in the future / non-object payload / unknown field | Integration | 400 with a specific code |
| POST 3 events; verify intact | Integration | Happy-path chain, genesis link, linked hashes |
| 10 parallel POSTs over real HTTP; verify intact; sequences 1..10 unique | Integration | Lock prevents split-brain `previousHash` |
| Each filter alone and combined; both clocks | Integration | Query spec |
| Page through results; size cap | Integration | Order by `sequence_num`, max 200 |
| `UPDATE` payload in DB; verify reports break at that sequence | Integration | Tamper evidence (the assignment's script) |
| `UPDATE recorded_at` in DB; verify reports break | Integration | Backdating ingest time is not a quiet edit |
| `PUT`/`DELETE` on `/audit/events` | Integration | 405: append-only surface |
| No credentials on write/query/verify | Integration | 401 |
| API key on verify | Integration | 403 |
| JWT without `audit.write` on POST | Integration | 403 |

**Manual flow** (README; implement with Scenario A APIs):

1. `POST /audit/events` with `X-API-Key: als_ingest_dev_key_do_not_use_in_prod`
2. `GET /audit/events` with the same key and a filter
3. `POST /auth/token` as `ops-admin`; `GET /audit/verify` with Bearer → `intact: true`
4. Superuser: `UPDATE audit_records SET payload = '{"tampered":true}' WHERE sequence_num = 2;`
5. `GET /audit/verify` with Bearer → `intact: false`, sequence 2, `CONTENT_HASH_MISMATCH`

---

## Out of scope for A

Retention, redaction, export, compliance reports (B and C). Corporate IdP/JWKS (local `POST /auth/token` only). Physical row delete.
