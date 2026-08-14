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
- OpenAPI UI: http://localhost:8080/swagger-ui.html

## Tests

```bash
./gradlew test
```

Integration tests start PostgreSQL 16 via Testcontainers (skipped if Docker is not running).

## Build

```bash
./gradlew build
```
