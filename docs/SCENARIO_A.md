# Scenario A — Core Audit Log Service

Greenfield: write, query, and verify a global SHA-256 hash chain. The assignment is validated through these APIs — write, query, verify, then tamper in SQL and verify again.

Design details: [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Requirement interpretation

| Spec item | Engineering meaning |
|-----------|---------------------|
| Write API | Accept `eventType`, `actorId`, `resourceType`, `resourceId`, `payload`; assign timestamp on the server |
| Append-only | No PUT/PATCH/DELETE for event content |
| Query API | Filter any combination of actor, resource, event type, time range; paginate |
| Hash chain | Each row stores `content_hash` and `previous_hash` |
| `GET /audit/verify` | Intact vs first broken record and violation type |

**Timestamp choice:** server-assigned `Instant` at persist. Documented in OpenAPI. Prevents caller backdating.

---

## Decomposition

Dependencies run top to bottom. Do not start a task until its predecessors exist.

| # | Task | Intent | Constraints | Acceptance |
|---|------|--------|-------------|------------|
| A1 | Bootstrap | Runnable Spring Boot 3 / Java 21 app | Maven, Docker Compose Postgres 16, Flyway, actuator health | `docker compose up -d` + `./mvnw spring-boot:run`; `/actuator/health` is UP |
| A2 | Schema + entity | Persist events | Flyway `V1`; unique `sequence_num`; JSONB payload | Migration applies on empty DB; entity maps all V1 columns |
| A3 | `HashChainService` | Deterministic SHA-256 | Sorted-key canonical JSON; genesis = 64 zero hex chars | Golden unit tests: known input → known hash; first record uses genesis |
| A4 | `AuditWriteService` | Append one chain link | One transaction; advisory lock; no client `id`/`sequence`/`hash` | POST returns `id`, `sequence`, `contentHash`, `timestamp`; sequence is n+1 |
| A5 | `AuditQueryService` | Filter + page | All filters optional and combinable; max page size 200 | Integration tests for each param alone and together; stable `sequence_num` order |
| A6 | `AuditVerifyService` | Detect tamper | Walk **all** rows by sequence; stop at first violation | Intact after honest writes; SQL payload edit → `CONTENT_HASH_MISMATCH` at that sequence |
| A7 | Controllers + errors | HTTP contract | `@Valid` DTOs; no update/delete mappings | OpenAPI at `/swagger-ui.html`; error envelope `{error, code, timestamp}` |

---

## Write contract

`POST /audit/events`

```json
{
  "eventType": "USER_LOGIN",
  "actorId": "user-123",
  "resourceType": "SESSION",
  "resourceId": "sess-abc",
  "payload": { "ip": "10.0.0.1" }
}
```

Rejected if required strings are blank or payload exceeds 64KB. Response includes server `timestamp` and hashes. Implementation: lock → load head → `sequence = head+1`, `previousHash = head.contentHash or GENESIS` → hash → insert.

---

## Query contract

`GET /audit/events`

Query params: `actorId`, `resourceType`, `resourceId`, `eventType`, `from`, `to`, `page`, `size`. Time bounds are inclusive on `timestamp`. Default size 50.

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

---

## Validation

| Test | Type | Proves |
|------|------|--------|
| Known payload → known hash; genesis on sequence 1 | Unit | Canonicalization is stable |
| POST 3 events; verify intact | Integration | Happy-path chain |
| 10 parallel POSTs; verify intact; sequences unique and contiguous | Integration | Lock prevents split-brain `previousHash` |
| Each filter alone and combined | Integration | Query spec |
| Page through results | Integration | Order by `sequence_num` |
| `UPDATE` payload in DB; verify reports break at that sequence | Integration | Tamper evidence |

**Manual flow** (also in README after bootstrap):

1. POST several events
2. GET with filters
3. GET `/audit/verify` → `intact: true`
4. `UPDATE audit_records SET payload = '{"tampered":true}' WHERE sequence_num = 2;`
5. GET `/audit/verify` → broken, sequence 2, `CONTENT_HASH_MISMATCH`

---

## Out of scope for A

Retention, redaction, export, compliance reports (B and C). Auth beyond input validation. Physical row delete.
