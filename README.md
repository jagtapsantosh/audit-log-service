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

## API (Scenarios A–C)

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/audit/events` | `X-API-Key` or JWT, scope `audit.write` | Append one event |
| `GET` | `/audit/events` | `X-API-Key` or JWT, scope `audit.read` | Filter + paginate |
| `GET` | `/audit/verify` | JWT only, scope `audit.read` | Walk the chain, report integrity |
| `GET` | `/audit/export` | `X-API-Key` or JWT, scope `audit.read` | Verifiable bundle for one actor or resource |
| `POST` | `/audit/events/{id}/redact` | JWT only, scope `audit.admin` | Mask payload fields via overlay |
| `POST` | `/audit/admin/archive` | JWT only, scope `audit.admin` | Run the retention sweep |
| `GET` | `/audit/compliance/access-report` | JWT only, scope `audit.compliance` | Access to `CLIENT_ACCOUNT` data |
| `GET` | `/audit/compliance/access-report/export` | JWT only, scope `audit.compliance` | Same report as JSON or CSV file |
| `POST` | `/auth/token` | public, rate-limited | Prototype JWT mint |

There is no update or delete endpoint for audit events; `PUT`/`DELETE` on `/audit/events` return **405**.
Redaction and archiving are `POST`s that add metadata beside a record — neither rewrites a stored
payload, a clock, or a hash, so both leave the chain verifiable.

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

## Scenario B validation script

Retention, redaction, and bulk export. Output below is captured from a real run.

### 1. Redact a sensitive field (JWT `audit.admin`)

Append an event whose payload holds sensitive values, then redact two paths — one top level, one
nested:

```bash
TOKEN=$(curl -sS -X POST http://localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"client_id":"ops-admin","client_secret":"ops-admin-secret-dev","scope":"audit.read audit.admin"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')

curl -sS -X POST http://localhost:8080/audit/events \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" \
  -d '{"eventType":"ACCOUNT_VIEWED","actorId":"user-123","resourceType":"CLIENT_ACCOUNT",
       "resourceId":"acct-1","occurredAt":"2026-08-14T11:30:00Z",
       "payload":{"accountNumber":"1234-5678-9012","customer":{"ssn":"111-22-3333","tier":"gold"},"ip":"10.0.0.1"}}'

curl -sS -X POST http://localhost:8080/audit/events/6/redact \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"fieldPaths":["accountNumber","customer.ssn"],"reason":"GDPR erasure request 42"}'
```

```json
{"id":6,"sequence":6,"eventType":"ACCOUNT_VIEWED","actorId":"user-123",
 "payload":{"ip":"10.0.0.1","customer":{"ssn":"[REDACTED]","tier":"gold"},"accountNumber":"[REDACTED]"},
 "contentHash":"3bb45fd9...","previousHash":"ce227809...","status":"ACTIVE",
 "redactedFields":["accountNumber","customer.ssn"]}
```

`redactedBy` is taken from the JWT subject; sending it in the body is rejected as an unknown field.
Unknown paths return **400** `UNKNOWN_FIELD_PATH`, array indexes return **400** `INVALID_FIELD_PATH`,
and an API key gets **403**. Redacting the same path twice is a no-op.

### 2. The chain is still intact — and a real SQL edit is still caught

```bash
curl -sS http://localhost:8080/audit/verify -H "Authorization: Bearer $TOKEN"
# {"intact":true,"totalRecords":6}

docker compose exec postgres psql -U auditlog -d auditlog -t \
  -c "select payload::text from audit_records where id = 6;"
# {"ip": "10.0.0.1", "customer": {"ssn": "111-22-3333", ...}, "accountNumber": "1234-5678-9012"}
```

The original payload is still stored, which is exactly why verification passes after a redaction — and
why redaction is not a laundering path for tampering:

```bash
docker compose exec postgres psql -U auditlog -d auditlog \
  -c "UPDATE audit_records SET payload = '{\"accountNumber\":\"0000-0000-0000\"}' WHERE id = 6;"

curl -sS http://localhost:8080/audit/verify -H "Authorization: Bearer $TOKEN"
```

```json
{"intact":false,"totalRecords":6,"firstViolation":{"sequence":6,"recordId":6,
 "violationType":"CONTENT_HASH_MISMATCH","expectedHash":"a000face...","actualHash":"3bb45fd9...",
 "detail":"stored contentHash does not match a re-hash of the stored record"}}
```

Trade-off owned: the overlay hides values from API consumers, not from anyone with a SQL connection.
Production would add field-level encryption.

### 3. Retention sweep

The window is measured against `recordedAt`, so a backdated `occurredAt` cannot keep a row hot. The
default is 365 days; start the service with `AUDIT_RETENTION_DAYS=0` to see the sweep act on a fresh
database.

```bash
curl -sS -X POST http://localhost:8080/audit/admin/archive -H "Authorization: Bearer $TOKEN"
# {"archived":6,"cutoff":"2026-08-14T16:41:42.503743Z","retentionDays":0}

curl -sS "http://localhost:8080/audit/events" -H "X-API-Key: $KEY"
# totalElements: 0   — archived records leave normal reads

curl -sS "http://localhost:8080/audit/events?includeArchived=true" -H "X-API-Key: $KEY"
# totalElements: 6, status ARCHIVED, archivedAt set, contentHash unchanged

curl -sS http://localhost:8080/audit/verify -H "Authorization: Bearer $TOKEN"
# {"intact":true,"totalRecords":6}   — no false break from archiving
```

Nothing is ever deleted: a hard delete would leave a sequence gap or orphan a successor's
`previousHash`, which verification would correctly report as a break. The cost is storage growth, which
production would address with partitioning or tiering. A daily sweep also runs on a schedule
(`audit.retention.sweep.enabled`, `audit.retention.sweep.cron`).

### 4. Export a verifiable bundle

```bash
curl -sS -o bundle.json "http://localhost:8080/audit/export?actorId=user-123" -H "X-API-Key: $KEY"
```

```json
{
  "exportVersion": "1.0",
  "exportedAt": "2026-08-14T16:40:27.759066Z",
  "filter": { "actorId": "user-123" },
  "genesisHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "records": [
    {
      "sequence": 6, "eventType": "ACCOUNT_VIEWED", "actorId": "user-123",
      "resourceType": "CLIENT_ACCOUNT", "resourceId": "acct-1",
      "occurredAt": "2026-08-14T11:30:00Z", "recordedAt": "2026-08-14T16:34:31.732342Z",
      "contentHash": "3bb45fd9...", "previousHash": "ce227809...",
      "payload": { "ip": "10.0.0.1", "customer": { "ssn": "[REDACTED]", "tier": "gold" },
                   "accountNumber": "[REDACTED]" },
      "redactedFields": ["accountNumber", "customer.ssn"]
    }
  ],
  "bundleHash": "75050ee7a0e7d9573f8754db56657a1056a0ec10c14084e873420dfae2ebcf07"
}
```

Requires `actorId` or `resourceId` (**400** `EXPORT_SUBJECT_REQUIRED` otherwise). Archived records are
included, because an export is evidence. Bundles are capped at 10,000 records.

### 5. Verify the bundle as the recipient

`ExportVerifier` is a standalone class with no Spring, database, or network dependency:

```bash
./gradlew verifyExport --args=bundle.json
```

```
bundleHashValid=true intact=true
records=1 rehashed=0 skippedRedacted=1
findings=none
```

Edit any byte of the file and it fails (a non-zero exit shows up as `BUILD FAILED`):

```
bundleHashValid=false intact=false
records=1 rehashed=0 skippedRedacted=1
findings=[bundleHash mismatch: file declares 75050ee7... but its contents hash to 7970aca6...]
```

**What the bundle does and does not prove.** A filtered export is a *sparse slice* of the global
chain: sequence numbers have gaps and most `previousHash` values point at records that are not in the
file, so a recipient cannot replay `/audit/verify` from it. The algorithm is therefore:

1. Recompute `bundleHash` over the canonical document with `bundleHash` removed → proves the file has
   not been altered since export.
2. Re-hash each record whose `redactedFields` is empty and compare to the server's `contentHash` →
   catches an edit even if the attacker re-seals `bundleHash`.
3. Skip that re-hash for redacted records: the file holds a masked payload while the hash covers the
   original. Their integrity stays with `GET /audit/verify` on the service.
4. Never fail on sequence gaps.

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

The `regulator` client serves `/audit/compliance/*` (JWT `audit.compliance`). It cannot call admin
archive/redact (**403**). An ingest API key and the `ops-admin` token are also **403** on compliance
paths: a leaked write credential must not pull a regulator report.

## Scenario C validation script

The product sentence was "Regulators need to be able to audit access to client account data." The
clarified contract is in [docs/SCENARIO_C.md](docs/SCENARIO_C.md): only `CLIENT_ACCOUNT` events of
types `ACCOUNT_VIEWED`, `ACCOUNT_UPDATED`, `STATEMENT_DOWNLOADED`, `PERMISSION_GRANTED`.

```bash
REG=$(curl -sS -X POST http://localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"client_id":"regulator","client_secret":"regulator-secret-dev","scope":"audit.compliance"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')

curl -sS "http://localhost:8080/audit/compliance/access-report?resourceId=acct-1" \
  -H "Authorization: Bearer $REG"
```

The report always carries `reportId`, `generatedAt`, and `chainHeadHash` (the live chain head, which
may be a non-access event). `USER_LOGIN` and `PERMISSION_GRANTED` on a non-account resource do not
appear. Optional `actorId`, `from`, `to` (on `occurredAt`), `page`, `size`. Archived rows are
included. Redacted payload paths show `[REDACTED]`.

```bash
curl -sS -o access-report.json \
  "http://localhost:8080/audit/compliance/access-report/export" \
  -H "Authorization: Bearer $REG"
curl -sS -o access-report.csv \
  "http://localhost:8080/audit/compliance/access-report/export?format=csv" \
  -H "Authorization: Bearer $REG"
```

CSV columns: `sequence,eventType,actorId,resourceType,resourceId,occurredAt,recordedAt,status,archivedAt,contentHash,previousHash,payload,redactedFields`.

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
| `AuditQueryServiceTest` | Unit (Mockito) | Page defaults and clamping, inverted ranges, overlay applied on read |
| `RedactionOverlayTest` | Unit | Path syntax, nested paths, unknown paths, arrays rejected, stored payload untouched |
| `RedactionServiceTest` | Unit (Mockito) | Idempotency, all-or-nothing validation, operator identity, micros precision |
| `RetentionServiceTest` | Unit (Mockito) | Cutoff maths on the ingest clock, zero-day window, no delete path |
| `ExportBundleServiceTest` | Unit (Mockito) | Bundle hash stability and sensitivity, exact wire format, size cap |
| `ExportVerifierTest` | Unit | Tampered files, re-sealed files, gaps, redacted records, malformed bundles |
| `ChainVerificationIT` | Integration | The Scenario A validation script, including the SQL tamper |
| `AuditEventApiIT` | Integration | Write contract, filters, paging, `timestamp` alias, 405 on update/delete |
| `ConcurrentAppendIT` | Integration | 10 parallel writers produce a contiguous, intact chain |
| `RetentionIT` | Integration | Sweep, `includeArchived`, mixed ACTIVE/ARCHIVED verify, redact-after-archive, auth |
| `RetentionDefaultWindowIT` | Integration | Default 365-day window does not archive a fresh write, even with an old `occurredAt` |
| `RedactionIT` | Integration | Masked reads, verify still intact, SQL tamper still caught, auth, error codes |
| `ExportIT` | Integration | Sparse slices, combined actor+resource filter, recipient-side verification, archived records, auth |
| `ComplianceReportServiceTest` | Unit (Mockito) | Frozen access scope, head hash vs last access event, summary vs page, overlay, export cap |
| `ComplianceCsvTest` | Unit | Documented columns; payload quoting; redacted values |
| `ComplianceReportIT` | Integration | Access vs noise, filters, redaction, archive included, CSV/JSON export, 401/403 matrix |
| `SecurityFlowIT` | Integration | 401/403 matrix across both credential types, including regulator vs admin |

Integration tests start PostgreSQL 16 via Testcontainers and are skipped (not failed) when Docker is
not running; unit tests always run. Known gaps and trade-offs are listed in
[docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md).

## Build

```bash
./gradlew build
```
