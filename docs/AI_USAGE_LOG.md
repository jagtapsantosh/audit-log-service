# AI Usage Log

Traceability of AI-assisted work. Append an entry per task. Engineer owns all accepted output.

Format:

```
## [YYYY-MM-DD] Task: <name>
- Prompt: ...
- Accepted: ...
- Modified: ...
- Rejected: ...
- Rationale: ...
```

---

## [2026-08-14] Task: Implementation plan from assignment spec

- Prompt: Create a detailed implementation plan from the audit log service assessment PDF. Chose Java + Spring Boot + PostgreSQL; cover Scenarios A, B, and C.
- Accepted: Layered API / domain / persistence design; global SHA-256 chain; server-assigned timestamps; Flyway V1/V2; Testcontainers; deliverable file list matching §7 of the assignment.
- Modified: Tightened hash canonicalization (sorted keys, UTF-8, no whitespace) and concurrent-write locking after reviewing race conditions on `previousHash`.
- Rejected: Python/FastAPI and Node stacks (engineer chose Java). Per-resource sub-chains (verify/export harder). SHA-512 (no benefit for this threat model). Hard-delete retention (false verify breaks).
- Rationale: Assignment scores engineering judgment and AI process, not only code. Plan had to freeze trade-offs before implementation. Server-only timestamps were the initial pick; later reversed to dual clocks (see 2026-08-14 dual-clock entry).

---

## [2026-08-14] Task: Repository bootstrap

- Prompt: Create empty repo `audit-log-service` with `IMPLEMENTATION_PLAN.md`, empty `ATTESTATION.md` and `README.md`.
- Accepted: Git repo on `main`; plan copied in full; attestation later filled with name/email/start date.
- Modified: None material.
- Rejected: Committing immediately (engineer did not request a commit). Scaffolding application code in the same step (plan-only repo first).
- Rationale: Assignment wants history from analysis through implementation. Empty-then-fill is an honest first commit shape.

---

## [2026-08-14] Task: Architecture diagram visibility

- Prompt: Why the architecture diagram in `IMPLEMENTATION_PLAN.md` was not visible; fix it.
- Accepted: Hand-authored SVG at `docs/architecture.svg`; markdown image embed; Mermaid kept as collapsed source with quoted subgraph titles.
- Modified: Removed a Cursor-specific explanation that did not belong in an assignment document.
- Rejected: Figma `generate_diagram` (wrong artifact; reviewers read the repo, not FigJam). Mermaid-only fix (Cursor/VS Code preview still would not draw it).
- Rationale: Reviewers and local preview both need a real image; Mermaid remains editable source.

---

## [2026-08-14] Task: docs/ implementation-requirement files

- Prompt: Create `ARCHITECTURE.md`, `SCENARIO_A.md`, `SCENARIO_B.md`, `SCENARIO_C.md`, `AI_USAGE_LOG.md`, `ENGINEERING_SUMMARY.md` with implementation-requirement detail from the plan.
- Accepted: Six files as named in the repo layout; Scenario C clarification written **before** compliance code; redaction overlay vs re-hash documented as a rejected alternative.
- Modified: Canonical hash field set specified as API names with lexicographic key order (not DB column names). Export verifier semantics: `bundleHash` protects the file; per-record hashes cannot be recomputed from redacted payloads.
- Rejected: Pretending implementation was already done. Filling Scenario C with SSO/PDF to look complete.
- Rationale: Assignment requires architecture, three scenario write-ups, AI traceability, and an engineering summary in the repository — not only a plan file.

---

## [2026-08-14] Task: Spring Boot Gradle bootstrap

- Prompt: Generate and commit the basic Spring Boot structure from the implementation plan, using Gradle instead of Maven, and update all documents.
- Accepted: Spring Boot 3.5.16 / Java 21 Gradle Kotlin DSL; package layout `api` / `domain` / `persistence` / `config`; Flyway V1; Docker Compose Postgres 16; actuator; SpringDoc; Testcontainers context-load test; README run commands.
- Modified: Build tool Maven → Gradle Wrapper (`./gradlew bootRun`). Docs, `.cursorrules`, and repo layout updated to match.
- Rejected: Spring Boot 4 from current start.spring.io (plan is Boot 3). Maven Wrapper. Implementing write/query/verify APIs in the same commit (bootstrap only).
- Rationale: Assignment history should show scaffold before domain logic. Gradle was an explicit engineer choice over the planned Maven default.

---

## [2026-08-14] Task: Dual-clock timestamps

- Prompt: Keep the dual timestamps for the actual event and audit service log time tracking. Engineer chose dual clocks.
- Accepted: `occurredAt` (client, required) + `recordedAt` (server `Instant` at persist), both in the hash. Query/compliance `from`/`to` on `occurredAt`; optional `recordedFrom`/`recordedTo`; retention on `recordedAt`. Reject `occurredAt` more than 5 minutes after `recordedAt`. V1 revised in place (`occurred_at`/`recorded_at`; dropped `timestamp` and unhashed `created_at`).
- Modified: Reversed the earlier server-only timestamp decision. Hash canonical field list now includes both clocks.
- Rejected: Caller-only time (no ingest evidence). Server-only time (loses delayed/offline event time). Keeping a third `created_at` column (redundant with `recordedAt`). Changing database away from PostgreSQL.
- Rationale: Dual clocks are the production audit pattern: event time for regulators, ingest time for tamper/ops/retention. Postgres remains an engineering fit (`JSONB`, advisory lock, Testcontainers), not a spec mandate.

---

## [2026-08-14] Task: Hybrid API security

- Prompt: Production-grade API security was missing from the plan. Brainstorm and freeze an industry-standard mechanism. Engineer chose hybrid: JWT for interactive/compliance, API keys for service-to-service ingest.
- Accepted: Hashed `X-API-Key` (config, peppered SHA-256) for write/query/export; JWT Bearer for verify/admin/compliance; scopes `audit.write|read|admin|compliance`; prototype `POST /auth/token` (client credentials, ~15 min HMAC JWT); production IdP/JWKS/TLS. Rate limits, no credential logging, 401/403 envelope.
- Modified: Replaced “optional API key later” and “SSO fully scoped out.” Compliance now requires JWT `audit.compliance`; SSO remains a production IdP, not a fake portal.
- Rejected: Unauthenticated APIs. API keys on redact/archive/verify/compliance (privilege escalation if an ingest key leaks). Implementing Spring Security in this freeze (contract only, same as dual clocks).
- Rationale: Assignment does not specify auth; a production-grade audit API still needs least privilege. Hybrid matches how ingest services vs operators actually call the system.

---

## [2026-08-14] Task: Implement hybrid API security

- Prompt: Add evaluator credentials to README and code the security changes.
- Accepted: Spring Security resource server (HS256 JWT); hashed `X-API-Key` filter; `POST /auth/token` (JSON + form); JWT-only managers for verify/admin/compliance; 401/403 JSON envelope; token rate limit; sample keys in README; OpenAPI apiKey + bearer schemes; `prod` profile disables Swagger.
- Modified: Write/query/verify still return 404 after successful auth until Scenario A controllers exist — README documents that so evaluators can still prove 401/403/404.
- Rejected: Real corporate IdP/JWKS. Logging credentials. API keys on verify/admin/compliance.
- Rationale: Evaluator must run both mechanisms on a laptop with only Docker + JDK.

---

## Later entries

Append here during bootstrap, hash-chain implementation, tests, redaction, export, and compliance work. For each: what was prompted, what was kept, what was edited, what was thrown away, and why.
