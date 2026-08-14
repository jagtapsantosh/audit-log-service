# Engineering Summary

Plan and rationale for the tamper-evident audit log prototype. Update the **Implementation status** section as code lands. Design decisions below are frozen unless a later entry in [AI_USAGE_LOG.md](AI_USAGE_LOG.md) records a change.

---

## Plan / rationale

Build a single Spring Boot service over PostgreSQL that (1) appends events onto a global SHA-256 hash chain, (2) archives and redacts without lying about integrity, (3) answers a narrowed compliance question with an explicit scope boundary.

Why this shape:

- **One global chain** — verify and export stay linear; the assignment’s validation script is “walk the chain.”
- **Dual clocks** — client `occurredAt` (event time) plus server `recordedAt` (ingest time), both hashed. The PDF allows either; both is the production-grade audit pattern. Query/compliance use `occurredAt`; retention uses `recordedAt`.
- **Hybrid API security** — hashed API keys for ingest; JWT for verify/admin/compliance. Least-privilege scopes. Local `POST /auth/token` stands in for a corporate IdP.
- **Soft archive + redaction overlay** — the two Scenario B constraints (don’t false-break verify; don’t destroy original hashes) both forbid mutating hashed bytes.
- **Clarify C before coding** — the product sentence is not an API. [SCENARIO_C.md](SCENARIO_C.md) is the contract.

Sequence: freeze (export subsequence, canonical JSON, empty-chain verify, trigger vs superuser tamper, V2 SQL) → A (MVP APIs + tamper test) → B (retention, redaction, export) → C (report) → README / attestation / this summary. Details: [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md).

---

## Artifacts

| Artifact | Location |
|----------|----------|
| Plan | `IMPLEMENTATION_PLAN.md` |
| Architecture | [ARCHITECTURE.md](ARCHITECTURE.md), `docs/architecture.svg` |
| Scenario A | [SCENARIO_A.md](SCENARIO_A.md) |
| Scenario B | [SCENARIO_B.md](SCENARIO_B.md) |
| Scenario C | [SCENARIO_C.md](SCENARIO_C.md) |
| AI trace | [AI_USAGE_LOG.md](AI_USAGE_LOG.md) |
| Attestation | `ATTESTATION.md` |
| Runbook | `README.md` |
| OpenAPI | SpringDoc `/swagger-ui.html` |
| Build | `build.gradle.kts`, Gradle Wrapper |
| Code | `com.auditlog` — Spring Boot 3.5, Flyway V1 (`occurred_at` / `recorded_at`) + V2 (retention, redaction) |
| Bundle verifier | `com.auditlog.domain.ExportVerifier`, `./gradlew verifyExport --args=bundle.json` |

---

## Implementation status

| Area | Status |
|------|--------|
| Design / docs | Done (2026-08-14); dual clocks, hybrid auth, pre-impl freeze |
| Scenario A bootstrap (Gradle, Docker Compose, Flyway V1, health) | Done (2026-08-14) |
| Hybrid API security (API keys + JWT token mint) | Done (2026-08-14) |
| Pre-impl freeze (export subsequence, canonical JSON, empty-chain, trigger vs tamper, V2 SQL) | Done (2026-08-14) |
| Scenario A APIs + tests (write, query, verify, tamper detection) | Done (2026-08-14); 66 tests green |
| Scenario B code + tests (retention, redaction, export + verifier) | Done (2026-08-14); 182 tests green, no Scenario A regressions |
| Scenario C code + tests | Done (2026-08-14); 208 tests green, no A/B regressions |
| README runbook | Setup, API table, Scenario A–C validation scripts, test matrix |
| Append-only DB trigger | Not implemented, by decision (needs an app role distinct from the tamper role; see Limitations) |

---

## Risks / trade-offs / validation

| Risk | Mitigation | How we will know |
|------|------------|------------------|
| Concurrent append shares `previousHash` | Advisory lock taken before reading the chain head | `ConcurrentAppendIT`: 10 parallel writers, sequences 1..10, chain intact |
| JSON serialization drifts → false verify failures | Frozen canonical rules (`JsonNode` payload, sorted keys, golden hex) | Unit tests on known vectors and pre-image |
| **`jsonb` re-renders numbers** (`1e2` → `100`) → honest record fails verify | Numbers canonicalized to plain decimal, trailing zeros stripped | Unit test per spelling + an IT that writes `1.0`/`1.50`/`1e2` then verifies |
| **`timestamptz` truncates to microseconds** → nanosecond input fails verify | Both clocks truncated to microseconds before hashing | Unit test; ITs assert returned `occurredAt` |
| App trigger blocks assigned SQL tamper | No trigger; app-layer enforcement only, so the evaluator's tamper step works as written | Evaluator script: UPDATE sequence 2 → `CONTENT_HASH_MISMATCH` |
| Export-by-actor looks like a broken chain | Sparse global subsequence; `bundleHash` is tamper-since-export; gaps are not a failure | `ExportVerifierTest` and `ExportIT` with gaps + redacted vs unredacted |
| Redaction mutates hashed payload | Overlay table; verify uses original JSONB | `RedactionIT`: redact → verify intact; stored payload still original; SQL tamper still `CONTENT_HASH_MISMATCH` |
| Archive looks like a chain break | Soft status only; verify reads all rows | `RetentionIT`: mixed ACTIVE/ARCHIVED, verify intact, nothing deleted |
| Retention metadata becomes a way to mutate records | Only `status` / `archived_at` / `has_redactions` are mutable, via two column-scoped UPDATE statements; the entity has no setters | Code review of `AuditRecordRepository`; hashed columns stay `updatable = false` |
| Bundle format drifts from what a recipient hashes | Domain bundle serialized directly; tests assert the exact field set | Caught in practice: derived accessors leaked `filter.empty` / `records[].redacted` into a real download, fixed with `@JsonIgnore` and locked by tests |
| A backdated `occurredAt` keeps a record out of retention | Sweep matches on `recordedAt` only | `RetentionServiceTest` asserts the cutoff is computed from the ingest clock |
| Scenario C expands into identity/PDF/filings | Written scope boundary | API matches [SCENARIO_C.md](SCENARIO_C.md) only |
| Report includes logins or non-account events | Frozen `AccessScope`; caller cannot pass `eventType`/`resourceType` | `ComplianceReportIT` seeds noise (`USER_LOGIN`, `RECORD_UPDATED`, `PERMISSION_GRANTED` on `ROLE`) and asserts they are absent |
| Canonical form omits a hashed field | Freeze field list in ARCHITECTURE.md | Code review + hash unit tests |
| Caller backdates `occurredAt` at write | Hashed `recordedAt` is ingest evidence; retention uses `recordedAt` | Dual-clock docs + retention tests |
| Leaked ingest API key | JWT-only for verify, redact, archive, compliance | Auth matrix tests (API key → 403 on admin) |
| SQL delete of the **newest** records | Not detectable by a chain walk alone (see Limitations); needs a head anchor kept outside the table | Probed against a live stack: deleting the tail row returns `intact: true`, deleting a middle row returns `SEQUENCE_GAP` |

Validation gate for A (assignment script): write → query → verify intact → SQL update one payload → verify names that sequence and `CONTENT_HASH_MISMATCH`. **Met**, both as `ChainVerificationIT` and as the README script run against Docker Compose.

### Testing approach, and what is not covered

Unit tests (JUnit 5 + Mockito) own the parts where correctness is subtle and a database would only add noise: canonical bytes, the golden hash vector, lock ordering, every chain-violation type, redaction path handling, retention cutoff maths, and bundle hashing. Integration tests (Testcontainers, real PostgreSQL 16) own the parts where the database *is* the risk: `jsonb`/`timestamptz` round trips, the advisory lock under real parallel HTTP, filter SQL, the narrow retention/redaction UPDATE statements, and the assignment's SQL tamper.

Scenario B's tests are written around the two claims that are easy to get wrong: that a privacy or retention action never changes what a hash covers (redact and archive, then verify intact, then confirm the stored payload is still the original), and that a filtered export is checkable despite sequence gaps and masked payloads. The V2 migration was also applied to a populated Scenario A database rather than only to fresh Testcontainers instances.

A zero-day retention window (`audit.retention.days=0`) is used in `RetentionIT` instead of backdating `recorded_at`, because that column is hashed — rewriting it would register as tamper rather than as an old record.

Not covered, deliberately: load/performance testing of the O(n) verify walk on large chains; fault injection (killed transactions, connection loss); mutation testing; multi-instance deployment; concurrent redaction of the same path from two callers (the unique constraint turns it into a **409**, which is asserted only by construction, not by a race test). No static-analysis or CI gate yet — `./gradlew test` is the gate, and CI would be the first addition with more time.

---

## Assumptions

- Single process, single PostgreSQL; no multi-region replication of the chain.
- SHA-256 and a secret-free chain are enough for **detection**, not for preventing a DBA from rewriting the whole table (they can recompute a new chain; detection assumes the attacker misses at least one stored hash or an off-box copy).
- Payload is JSON objects with dotted paths for redaction; arrays out of scope for v1.
- Dual clocks: `occurredAt` is client-claimed event time (may be backdated at write); `recordedAt` is server ingest time. Query `from`/`to` and compliance reports use `occurredAt`; retention uses `recordedAt`.
- “Client account access” = `CLIENT_ACCOUNT` + four event types listed in Scenario C.
- Prototype auth is hybrid API keys + locally minted JWTs. Production uses TLS termination and a corporate IdP (JWKS); no local token endpoint.

---

## Limitations

- Append-only is enforced at the application layer: no mapped verbs, no delete anywhere in the persistence interfaces, hashed columns `updatable = false`, and the only two UPDATE statements name retention/redaction metadata columns explicitly. Preventing SQL-level rewrites needs a DB trigger scoped to a dedicated app role, which stays unimplemented by decision — the compose stack runs the app and the assignment's tamper step as the same role, and splitting them would change local credentials and Testcontainers wiring for a guarantee this prototype already frames as detection.
- **Truncation of the newest records is not detectable.** Deleting the tail leaves a prefix that still hashes and links correctly, so verify reports `intact: true` with a smaller `totalRecords`. Every other edit is caught: payload, actor, or clock changes as `CONTENT_HASH_MISMATCH`, a rewritten link as `PREVIOUS_HASH_BREAK`, and a deleted interior record as `SEQUENCE_GAP`. Closing the gap needs an anchor the DBA does not own — publishing the head `(sequence, contentHash)` to a WORM sink, or an external monitor that pins the last head it saw and alerts when the chain shrinks. The honest claim for this prototype is therefore that the chain proves nothing was altered *up to the head it currently claims*, not that no record was ever removed from the end.
- Verify is an O(n) walk with no checkpoints, so verification cost grows with the log.
- Overlay redaction does not hide plaintext from anyone with SQL access. It protects API consumers and export recipients only.
- Redaction is field-level and additive: there is no un-redact, and no record-level erasure. Field paths are dotted and cannot address array elements.
- Soft archive never reclaims disk, and there is no un-archive endpoint (a re-run of the sweep will not revert a record whose window later changes).
- Export is a subsequence of the global chain, not a replay of `/audit/verify`. Recipients cannot recompute per-record `contentHash` when the payload is redacted; they check `bundleHash` (and unredacted records only). `bundleHash` is unkeyed, so anyone who knows the algorithm can re-seal an edited file — which is why unredacted records are re-hashed individually, and why a signature would be the production upgrade.
- Bundles are capped at 10,000 records and built in memory rather than streamed.
- Compliance report is a live query with a pinned head hash, not a signed, stored filing. `chainHeadHash` is the global chain head, which may not itself be an access event.
- Prototype JWT mint is not corporate SSO. No multi-tenant isolation, no PDF.

These are deliberate timebox cuts, not accidental omissions. Production follow-ups: column encryption or field-level crypto, partition/tier old rows, signed reports, JWKS/IdP, mTLS.
