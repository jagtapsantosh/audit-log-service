# Test traceability

Maps each API and integrity risk to the test that exercises it. Run `./gradlew check` for the suite plus the JaCoCo gate.

| ID | Requirement / risk | Endpoint or surface | Tests |
| --- | --- | --- | --- |
| A-WRITE | Append assigns sequence, both clocks, genesis then links | `POST /audit/events` | `AuditWriteServiceTest`, `AuditEventApiIT`, `ChainVerificationIT` |
| A-QUERY | Filter + paging; `timestamp` alias | `GET /audit/events` | `AuditQueryServiceTest`, `AuditEventApiIT` |
| A-VERIFY | Intact walk; empty chain intact | `GET /audit/verify` | `AuditVerifyServiceTest`, `ChainVerificationIT` |
| A-TAMPER | Payload / clock SQL edit → `CONTENT_HASH_MISMATCH` | verify after JDBC UPDATE | `ChainVerificationIT` |
| A-GAP | Interior delete → `SEQUENCE_GAP` | verify | `AuditVerifyServiceTest` |
| A-TAIL | Newest-row delete → `TAIL_TRUNCATION` | verify vs `audit_chain_head` | `TailTruncationIT`, `AuditVerifyServiceTest` |
| A-CONCUR | Parallel writers: sequences 1..n, intact | write | `ConcurrentAppendIT` |
| A-CANON | Numbers / timestamps canonicalize | hash pre-image | `CanonicalJsonTest`, numeric IT in `AuditEventApiIT` |
| A-405 | No PUT/DELETE on events | `/audit/events` | `AuditEventApiIT`, `SecurityFlowIT` |
| B-REDACT | Overlay mask; stored JSONB unchanged; verify intact | `POST /audit/events/{id}/redact` | `RedactionIT`, `RedactionServiceTest` |
| B-REDACT-RACE | Concurrent same path: one overlay row | redact | `RedactionRaceIT` |
| B-ARCHIVE | Soft status; query hides; verify includes | `POST /audit/admin/archive` | `RetentionIT`, `RetentionDefaultWindowIT` |
| B-EXPORT | Sparse slice; recipient verifier; signature | `GET /audit/export` | `ExportIT`, `ExportBundleServiceTest`, `ExportVerifierTest` |
| C-REPORT | Frozen access scope; head hash; CSV/JSON | `/audit/compliance/*` | `ComplianceReportIT`, `ComplianceReportServiceTest`, `ComplianceCsvTest` |
| SEC-AUTHN | Missing / bad / expired / wrong-type creds | all protected paths | `SecurityFlowIT` |
| SEC-AUTHZ | Scope matrix; API key denied on JWT-only | verify, redact, archive, compliance | `SecurityFlowIT`, endpoint ITs |
| SEC-IDEMP | Same key + body replays; same key + different body 409 | `POST /audit/events` | `IdempotencyIT`, `AuditWriteServiceTest` |
| SEC-LIMIT | Oversized body 413; write/token rate 429 | filters | `RequestLimitIT` |
| SEC-PROD | Dev fallback secrets refused on `prod` | startup | `ProductionSecretsTest` |
| SEC-TOKEN | Local mint issues scoped JWT | `POST /auth/token` | `SecurityFlowIT` |
| FAULT-TX | Append failure does not publish a new head | write service | `AuditWriteServiceTest` |

Coverage artifact: `build/reports/jacoco/test/html/index.html` after `./gradlew check`.
