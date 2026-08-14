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

## Assessment credentials (local / evaluator only)

These plaintext values are for running the prototype. The service stores **hashes** (pepper + secret) in `application.yml`. Do not use them in production. Override with `AUDIT_SECURITY_PEPPER` and `AUDIT_JWT_SECRET`.

| Kind | id | Secret | Scopes |
|------|-----|--------|--------|
| API key | `ingest-service` | `als_ingest_dev_key_do_not_use_in_prod` | `audit.write`, `audit.read` |
| OAuth client | `ops-admin` | `ops-admin-secret-dev` | `audit.read`, `audit.admin` |
| OAuth client | `regulator` | `regulator-secret-dev` | `audit.read`, `audit.compliance` |

Header for ingest: `X-API-Key`. Header for JWT: `Authorization: Bearer <token>`.

### 1. API key (service ingest)

```bash
curl -sS -X POST http://localhost:8080/audit/events \
  -H "Content-Type: application/json" \
  -H "X-API-Key: als_ingest_dev_key_do_not_use_in_prod" \
  -d '{"eventType":"USER_LOGIN","actorId":"user-123","resourceType":"SESSION","resourceId":"sess-abc","occurredAt":"2026-08-14T11:30:00Z","payload":{"ip":"10.0.0.1"}}'
```

Until the write API is implemented this returns **404** (auth succeeded). Without the header: **401**. The same key on verify is **403** (JWT-only):

```bash
curl -sS -D - http://localhost:8080/audit/verify \
  -H "X-API-Key: als_ingest_dev_key_do_not_use_in_prod" -o /dev/null
```

### 2. JWT (OAuth 2.0 client credentials)

```bash
curl -sS -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"client_id":"ops-admin","client_secret":"ops-admin-secret-dev","scope":"audit.read audit.admin"}'
```

Form-encoded is also accepted (`grant_type` is ignored; this is a prototype token mint):

```bash
curl -sS -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d 'client_id=ops-admin&client_secret=ops-admin-secret-dev&scope=audit.read'
```

Use the `access_token` (about 15 minutes):

```bash
TOKEN='<paste access_token>'

curl -sS -D - http://localhost:8080/audit/verify \
  -H "Authorization: Bearer $TOKEN" -o /dev/null
```

JWT with `audit.read` is allowed on verify. Until the verify API exists this is **404** (auth succeeded). No credentials: **401**.

Regulator client (`regulator` / `regulator-secret-dev`) can call `/audit/compliance/*` once those APIs exist; it cannot call admin archive/redact (**403**).

Production: terminate TLS at a reverse proxy and validate JWTs from the corporate IdP (JWKS). Disable local mint (`POST /auth/token`) and OpenAPI (`--spring.profiles.active=prod`).

## Tests

```bash
./gradlew test
```

Integration tests start PostgreSQL 16 via Testcontainers (skipped if Docker is not running). Auth unit tests always run.

## Build

```bash
./gradlew build
```
