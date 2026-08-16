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

## [2026-08-14] Task: Pre-implementation freeze

- Prompt: Freeze critical gaps in the plan before Scenario A–C APIs (export subsequence, evaluator tamper script, append-only vs superuser, canonical JSON, complete V2 SQL, submission process).
- Accepted: `bundleHash` as tamper-since-export; `ExportVerifier` does not fail on sequence gaps and only recomputes `contentHash` when unredacted; empty-chain verify `intact: true` / `totalRecords: 0`; Flyway trigger for app role with `postgres` superuser tamper in README; Jackson sorted keys + `JsonNode` payload + Instant `toString` + golden hex; V2 SQL aligned with ARCHITECTURE (`has_redactions`, `audit_redactions`); commit per working slice; no assignment PDF in the shared repo; attestation date at the end; `./gradlew test` as quality gate.
- Modified: IMPLEMENTATION_PLAN V2 was only `status`/`archived_at` — completed to match ARCHITECTURE. Timeline marked bootstrap + auth done; next step is A APIs.
- Rejected: Per-resource chains, merkle/JWS reports, regulator SSO/JWKS, verify checkpoints, GitHub Actions now, updating `architecture.svg` unless controller names change.
- Rationale: The PDF scores judgment + process. Coding write/query/verify before freezing export/verify/trigger rules would overbuild a mini-chain or flake on JSON / empty verify / a trigger that blocks the assigned SQL tamper.

---

## [2026-08-14] Task: Implement Scenario A (write, query, verify, tamper tests)

- Prompt: Implement Scenario A following the frozen plan and scenario docs.
- Accepted: `CanonicalJson` + `HashChainService` with a golden vector; `AuditRecordStore` port so the domain does not depend on JPA; entity with every column `updatable = false`; repository extending bare `Repository` (no delete reachable); advisory lock before reading the chain head; keyset-paged verify with first-violation-only reporting; write/query/verify controllers with OpenAPI annotations; one error envelope; Micrometer counters/timer from ARCHITECTURE; 66 tests (unit + Testcontainers) including the assignment's SQL-tamper script.
- Modified: three corrections the AI's first pass got wrong or under-specified, each found by running the code rather than by review:
  1. **Numbers.** The plan said "hash payload as `JsonNode`", which is necessary but not sufficient: PostgreSQL `jsonb` re-renders `1e2` as `100`, so an honest record could fail verification after a round trip. Canonical numbers are now plain decimal with trailing zeros stripped.
  2. **Clock precision.** `timestamptz` keeps microseconds, so hashing a nanosecond `Instant` would break verify on re-read. Both clocks are truncated to microseconds before hashing.
  3. **Filter SQL.** The generated `:param IS NULL OR column = :param` pattern failed at runtime (`could not determine data type of parameter $9`); replaced with `column = COALESCE(:param, column)`, which is valid because these columns are NOT NULL.
- Also modified: `SecurityFlowIT` placeholders that asserted 404 for write/verify now assert real behaviour (400 validation, 200 intact), plus a new test that a read-only JWT cannot append. Added `timestamp` as a `@JsonAlias` for `occurredAt` so the PDF's field name works verbatim.
- Rejected: `JpaRepository` (exposes `delete*`); a DB trigger in Scenario A (would block the assignment's required SQL tamper, since compose runs the app and `psql` as the same role — deferred to B with a role split); returning a non-200 status for a broken chain (the report succeeded); verify checkpoints; per-resource chains.
- Rationale: the hash chain is only as trustworthy as its determinism, so the risky work was canonicalization and the write lock, not the controllers. Both hazards found above would have produced *false* tamper alerts in a demo — the worst possible failure for this service — so they are covered by named unit tests and asserted again through a real database.

---

## [2026-08-14] Task: End-to-end validation of Scenario A against the specification

- Prompt: Have you implemented Scenario A fully? Validate it end to end against the requirements and the plan.
- Accepted: the implementation as built. Forced a real test run (`cleanTest test`, since the previous run was served from the Gradle cache): 66 tests, no failures, no skips. Ran the README script verbatim against a freshly reset stack — empty-chain verify, three appends linking from genesis, filtered query, intact verify, `UPDATE` of one payload in `psql`, then verify naming sequence 2 and `CONTENT_HASH_MISMATCH`. Confirmed each contract claim independently: the `timestamp` alias, unknown-field rejection, non-object payload, blank field, future-clock rejection, every filter alone and combined, inclusive `from`/`to`, the 200 page cap, and the 401/403/405 matrix.
- Modified: documented one gap this pass exposed. A payload of `{"n":1.50,"m":1e2,"z":{"b":2,"a":1}}` survived the `jsonb` round trip (stored as `100` / `1.5`, keys reordered) with verify still intact, so canonicalization holds against a real database. But probing beyond the assignment's script showed that deleting the **newest** record leaves `intact: true`: the surviving prefix is self-consistent, so a chain walk has nothing to catch. Interior deletes, clock edits, field edits, and rewritten links were all caught. Recorded as a named limitation and risk in `ENGINEERING_SUMMARY.md` and as a detection boundary in `SCENARIO_A.md`, with the mitigation path (an off-box head anchor) stated rather than built.
- Also modified: untracked the confidential assignment PDF (`git rm --cached`) and added a `.gitignore` rule. It had been committed in `3e21120`, and §0.2 forbids re-hosting it in a repository that §7 requires sharing with the panel. The blob remains reachable in history; removing it there needs a rewrite and was deliberately left to the engineer.
- Rejected: implementing a head-anchor or verify checkpoints now (scope creep into B, and an anchor the same DBA can rewrite buys little without an external sink); a live 25-writer burst against the running app, since `ConcurrentAppendIT` already proves the lock over real parallel HTTP; editing `IMPLEMENTATION_PLAN.md` to record any of this, because the plan is frozen.
- Rationale: a green suite only proves the assertions someone thought to write, so the value of this pass was in the checks nobody had written — `jsonb` number re-rendering through the real database, and delete shapes the assignment's single-UPDATE script never exercises. The truncation gap is worth stating plainly: the service detects modification of what it holds, and cannot by itself prove that nothing was removed from the end.

---

## [2026-08-14] Task: Purge the confidential assignment PDF from git history

- Prompt: Rewrite the git history to remove the confidential PDF, without altering existing commits or files.
- Accepted: `git filter-branch --index-filter 'git rm --cached --ignore-unmatch -- <pdf>' -- main`, scoped to the single path and to `main`, with empty-commit pruning left off so no commit could disappear. The file had been added in the root-adjacent `3e21120` and lived in four commit trees before being removed in the tip commit, so the blob was still fully reachable. Then `git push --force-with-lease origin main`, which is why the lease matters: it refuses the push if the remote moved since the last fetch.
- Verified rather than assumed: all six commits survive with byte-identical messages, authors, committers, and both timestamps; every file blob in every commit (190 path/SHA pairs, excluding the PDF) is unchanged; the `HEAD` tree hash is still `82cf050`, proving the current working files were untouched; the path appears in zero commits locally and in zero commits on `origin/main`.
- Rejected: `git filter-repo` (would have needed installing, and its post-run `git reset --hard` is a hazard in a dirty tree); copying the repository to `/tmp` as a backup, since that would have duplicated confidential material outside the repo when `refs/original`, the untouched remote-tracking ref, and the reflog already provided three in-repo rollback paths; dropping `refs/original` immediately, kept for now as the rollback net.
- Limitation stated to the engineer: commit SHAs necessarily changed (a commit's identity includes its tree), and GitHub retains unreachable objects for a period, so the blob may remain fetchable by its SHA until their garbage collection runs. Full certainty there requires a request to GitHub Support.
- Rationale: §0.2 forbids re-hosting the assignment, while §7 requires submitting the repository *with* its history, so leaving the blob reachable would have handed Schwab's confidential brief back inside the deliverable. The care went into proving the rewrite was surgical, because "trust me, only the PDF changed" is not something a reviewer should have to take on faith in a repository whose whole subject is tamper evidence.

---

## [2026-08-14] Task: Implement Scenario B (retention, redaction, export)

- Prompt: Implement Scenario B carefully per the documentation and specification. Do not break previous functionality. Write tests for all edge cases.
- Accepted: the frozen design — soft archive on `recordedAt` (never delete); redaction as an overlay table so hashed payload is untouched; export as a sparse subsequence with `bundleHash` plus per-record re-hash only when unredacted. Application-layer append-only stays: hashed columns `updatable = false`, no delete methods, and the only two UPDATE statements name `status`/`archived_at`/`has_redactions`. JWT-only for redact and archive; API key or JWT `audit.read` for export. Standalone `ExportVerifier` with `./gradlew verifyExport`.
- Modified: `redactedBy` is taken from the JWT `sub` (the request DTO has no such field). Redaction is all-or-nothing per request, idempotent per path, max 50 paths, dotted paths only. Export includes archived records and is capped at 10,000. Bundle serialization is the domain object itself so `bundleHash` cannot drift. Added edge-case coverage the first pass did not have: default 365-day window does not archive a fresh write even with a six-year-old `occurredAt`; regulator JWT cannot redact; an archived record can still be redacted without breaking verify; actor and resource export filters combine; hash pre-image contains no retention/redaction metadata.
- Rejected: append-only DB trigger (still needs an app role distinct from the tamper role); array-index field paths; mutating payload and recomputing the hash; hard delete or tombstones; signing `bundleHash`; editing `IMPLEMENTATION_PLAN.md`.
- Rationale: Scenario B's two easy failures are a false verify break after archive/redact, and treating a filtered export as a mini-chain. Tests are written around those claims (redact then verify intact then SQL-tamper still `CONTENT_HASH_MISMATCH`; sparse sequences accepted by `ExportVerifier`; resealed edited unredacted records still fail content re-hash). Quality gate: `./gradlew cleanTest test` — 182 tests, 0 failures, 0 skips, including the original Scenario A suites.

---

## [2026-08-14] Task: Implement Scenario C (compliance access report)

- Prompt: Move on to Scenario C implementation.
- Accepted: the clarified requirement in `docs/SCENARIO_C.md` as the only C contract — `CLIENT_ACCOUNT` plus the four access event types, filterable by account/actor/`occurredAt`, JWT `audit.compliance` only, `chainHeadHash` as the live chain head at `generatedAt`, redaction-aware rows, archived rows included. JSON report + CSV/JSON download. No stored report table, no SSO, no PDF, no signed JWS.
- Modified: JSON report carries paging fields (`page`/`size`/`totalElements`/`totalPages`) that the spec example omitted, because C3 asked for a paginated report; summary totals are over the whole match set. Empty chain pins genesis as `chainHeadHash` so the field is always present. Export is capped at 10,000 rows. `chainHeadHash` is the global head, which a test proves can be a later `USER_LOGOUT` rather than the last access event.
- Rejected: letting the caller pass `eventType`/`resourceType` (would let a regulator redefine "access"); unredacted exam mode; corporate SSO; editing `IMPLEMENTATION_PLAN.md`.
- Rationale: the assignment scores how you handle an under-specified sentence. The work was freezing the enum and resource type *before* coding, then proving the report does not leak logins or non-account permission grants, and that the head pin is the live chain rather than "the last row in the report." Quality gate: `./gradlew cleanTest test` — 208 tests, 0 failures, 0 skips, including every A and B suite.

---

## [2026-08-16] Task: Close evaluation scorecard gaps

- Prompt: Understand the attached detailed evaluation scorecard and fix the gaps it listed for this audit-log-service.
- Accepted: Complete `ATTESTATION.md` (date, repo, commit, claim-to-evidence). Fail-closed `prod` secrets (`ProductionSecrets`). JaCoCo line/branch gate + GitHub Actions `./gradlew check`. Optional `Idempotency-Key` on write. Request size + write rate limits. Explicit deny-all CORS. `audit_chain_head` + `TAIL_TRUNCATION`. HMAC `bundleSignature` on exports. `RedactionRaceIT`, append-failure does not publish head. Traceability matrix. Flyway V3 for head + idempotency tables.
- Modified: Export wire format gained `bundleSignature` (hash pre-image still excludes hash and signature). Verify now fails when the published head is ahead of the table. Local token mint is disableable; JWKS URI is config-ready, not a live Okta integration.
- Rejected: Editing the frozen `IMPLEMENTATION_PLAN.md`. Faking a corporate IdP. A DB trigger that would break the assignment's same-role SQL tamper. Asymmetric export signatures. Multi-tenant resource ownership. Killing Postgres mid-transaction as a flaky IT.
- Rationale: The scorecard's Weak Borderline verdict was gated on attestation and production-security evidence, not a broken chain. The P0/P1 items that could be proven in this prototype are now executable tests; IdP/mTLS/WORM remain documented production follow-ups rather than a pretend integration.

## Later entries

Append here if a later change revises a frozen decision. For each: what was prompted, what was kept, what was edited, what was thrown away, and why.
