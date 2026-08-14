# Audit Log Service

Tamper-evident, append-only audit log. **Java 21**, **Spring Boot 3.5**, **Gradle**, **PostgreSQL 16**.

Design: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Prerequisites

- JDK 21 (`JAVA_HOME` pointing at a 21 JDK)
- Docker (local Postgres and Testcontainers)

## Run locally

```bash
docker compose up -d
./gradlew bootRun
```

- Health: http://localhost:8080/actuator/health
- OpenAPI UI: http://localhost:8080/swagger-ui.html (Authorize with `X-API-Key` or Bearer JWT)
- Token: `POST http://localhost:8080/auth/token`

To start over with an empty chain: `docker compose down -v && docker compose up -d`.

## API (Scenario A)

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/audit/events` | `X-API-Key` or JWT, scope `audit.write` | Append one event |
| `GET` | `/audit/events` | `X-API-Key` or JWT, scope `audit.read` | Filter + paginate |
| `GET` | `/audit/verify` | JWT only, scope `audit.read` | Walk the chain, report integrity |
| `POST` | `/auth/token` | public, rate-limited | Prototype JWT mint |

There is no update or delete endpoint for audit events; `PUT`/`DELETE` on `/audit/events` return **405**.

**Timestamps.** The write API takes the caller's `occurredAt` (the assignment's `timestamp`; the name
`timestamp` is accepted as an alias) and the server stamps `recordedAt` at ingest. Both are hashed.
`from`/`to` filter `occurredAt`; `recordedFrom`/`recordedTo` filter `recordedAt`. Rationale:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Assessment credentials (local / evaluator only)

These plaintext values are for running the prototype. The service stores **hashes** (pepper + secret) in `application.yml`. Do not use them in production. Override with `AUDIT_SECURITY_PEPPER` and `AUDIT_JWT_SECRET`.

| Kind | id | Secret | Scopes |
|------|-----|--------|--------|
| API key | `ingest-service` | `als_ingest_dev_key_do_not_use_in_prod` | `audit.write`, `audit.read` |
| OAuth client | `ops-admin` | `ops-admin-secret-dev` | `audit.read`, `audit.admin` |
| OAuth client | `regulator` | `regulator-secret-dev` | `audit.read`, `audit.compliance` |

Header for ingest: `X-API-Key`. Header for JWT: `Authorization: Bearer <token>`.

## Scenario A validation script

This is the assignment's validation path: write events, query them, verify the chain, modify a record
directly in the data store, verify again and see the break. Run it against a freshly started stack.

### 1. Append events (API key)

```bash
KEY='als_ingest_dev_key_do_not_use_in_prod'

for n in 1 2 3; do
  curl -sS -X POST http://localhost:8080/audit/events \
    -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
    -d "{\"eventType\":\"USER_LOGIN\",\"actorId\":\"user-12$n\",\"resourceType\":\"SESSION\",\"resourceId\":\"sess-$n\",\"occurredAt\":\"2026-08-14T11:3$n:00Z\",\"payload\":{\"ip\":\"10.0.0.$n\"}}"
  echo
done
```

Each response carries its chain position. Record 1 links to the genesis value; record 2 links to
record 1's `contentHash`:

```json
{"id":1,"sequence":1,"eventType":"USER_LOGIN","actorId":"user-121","resourceType":"SESSION","resourceId":"sess-1","payload":{"ip":"10.0.0.1"},"occurredAt":"2026-08-14T11:31:00Z","recordedAt":"2026-08-14T14:14:24.257027Z","contentHash":"b694a49f...","previousHash":"0000000000000000000000000000000000000000000000000000000000000000"}
{"id":2,"sequence":2,...,"contentHash":"44fd5033...","previousHash":"b694a49f..."}
```

### 2. Query

```bash
curl -sS "http://localhost:8080/audit/events?actorId=user-122" -H "X-API-Key: $KEY"
```

Any combination of `actorId`, `resourceType`, `resourceId`, `eventType`, `from`/`to`,
`recordedFrom`/`recordedTo`, `page`, `size` works (default size 50, max 200, ordered by sequence).

### 3. Verify the chain (JWT)

```bash
TOKEN=$(curl -sS -X POST http://localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"client_id":"ops-admin","client_secret":"ops-admin-secret-dev","scope":"audit.read"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')

curl -sS http://localhost:8080/audit/verify -H "Authorization: Bearer $TOKEN"
```

```json
{"intact":true,"totalRecords":3}
```

An empty chain also reports `{"intact":true,"totalRecords":0}`: there is nothing that could have been
altered.

### 4. Tamper directly in the data store

```bash
docker compose exec postgres psql -U auditlog -d auditlog \
  -c "UPDATE audit_records SET payload = '{\"tampered\":true}' WHERE sequence_num = 2;"
```

### 5. Verify again — the edit is detected

```bash
curl -sS http://localhost:8080/audit/verify -H "Authorization: Bearer $TOKEN"
```

```json
{"intact":false,"totalRecords":3,"firstViolation":{"sequence":2,"recordId":2,
 "violationType":"CONTENT_HASH_MISMATCH","expectedHash":"0dd690b5...","actualHash":"44fd5033...",
 "detail":"stored contentHash does not match a re-hash of the stored record"}}
```

`expectedHash` is what the stored fields hash to now; `actualHash` is the hash committed at write
time. Violation types are `CONTENT_HASH_MISMATCH`, `PREVIOUS_HASH_BREAK`, and `SEQUENCE_GAP`.
Editing either timestamp is detected the same way, because both clocks are hashed.

Reset before demoing again: `docker compose down -v && docker compose up -d`.

## Auth behaviour

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8080/audit/verify                     # 401
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8080/audit/verify -H "X-API-Key: $KEY" # 403
curl -sS -o /dev/null -w '%{http_code}\n' -X PUT http://localhost:8080/audit/events -H "X-API-Key: $KEY" # 405
```

Verify is JWT-only on purpose: a leaked ingest key must not be able to probe chain integrity. Errors
use one envelope, `{"error","code","timestamp"}`, including 401 and 403.

The token endpoint also accepts form encoding (`grant_type` is ignored; this is a prototype mint):

```bash
curl -sS -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d 'client_id=ops-admin&client_secret=ops-admin-secret-dev&scope=audit.read'
```

The `regulator` client will serve `/audit/compliance/*` in Scenario C; it cannot call admin
archive/redact (**403**).

Production: terminate TLS at a reverse proxy and validate JWTs from the corporate IdP (JWKS). Disable local mint (`POST /auth/token`) and OpenAPI (`--spring.profiles.active=prod`).

## Tests

```bash
./gradlew test
```

| Suite | Type | Covers |
|-------|------|--------|
| `CanonicalJsonTest`, `HashChainServiceTest` | Unit | Canonical bytes, golden hash vector, every hashed field, clock precision |
| `AuditWriteServiceTest` | Unit (Mockito) | Genesis link, sequence assignment, lock-before-read, clock skew, payload rules |
| `AuditVerifyServiceTest` | Unit (Mockito) | Empty chain, intact chain, all three violation types, first-violation-only |
| `AuditQueryServiceTest` | Unit (Mockito) | Page defaults and clamping, inverted ranges |
| `ChainVerificationIT` | Integration | The validation script above, including the SQL tamper |
| `AuditEventApiIT` | Integration | Write contract, filters, paging, `timestamp` alias, 405 on update/delete |
| `ConcurrentAppendIT` | Integration | 10 parallel writers produce a contiguous, intact chain |
| `SecurityFlowIT` | Integration | 401/403 matrix across both credential types |

Integration tests start PostgreSQL 16 via Testcontainers and are skipped (not failed) when Docker is
not running; unit tests always run. Known gaps and trade-offs are listed in
[docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md).

## Build

```bash
./gradlew build
```
