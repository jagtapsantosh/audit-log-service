# Engineering Summary

Plan and rationale for the tamper-evident audit log prototype. Update the **Implementation status** section as code lands. Design decisions below are frozen unless a later entry in [AI_USAGE_LOG.md](AI_USAGE_LOG.md) records a change.

---

## Plan / rationale

Build a single Spring Boot service over PostgreSQL that (1) appends events onto a global SHA-256 hash chain, (2) archives and redacts without lying about integrity, (3) answers a narrowed compliance question with an explicit scope boundary.

Why this shape:

- **One global chain** — verify and export stay linear; the assignment’s validation script is “walk the chain.”
- **Server timestamps** — the log is evidence; the writer must not pick the clock.
- **Soft archive + redaction overlay** — the two Scenario B constraints (don’t false-break verify; don’t destroy original hashes) both forbid mutating hashed bytes.
- **Clarify C before coding** — the product sentence is not an API. [SCENARIO_C.md](SCENARIO_C.md) is the contract.

Sequence: A (MVP APIs + tamper test) → B (retention, redaction, export) → C (report) → README / attestation / this summary. Details: [IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md).

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
| Runbook | `README.md` (after bootstrap) |
| OpenAPI | SpringDoc `/swagger-ui.html` (after APIs) |
| Code | `com.auditlog` — not scaffolded at the time this file was first written |

---

## Implementation status

| Area | Status |
|------|--------|
| Design / docs | Done (2026-08-14) |
| Scenario A code + tests | Not started |
| Scenario B code + tests | Not started |
| Scenario C code + tests | Not started |
| README runbook | Not started |

---

## Risks / trade-offs / validation

| Risk | Mitigation | How we will know |
|------|------------|------------------|
| Concurrent append shares `previousHash` | Transactional advisory lock | Parallel POST integration test; sequences contiguous |
| JSON serialization drifts → false verify failures | Canonical sorted-key JSON; golden hash tests | Unit tests on known vectors |
| Redaction mutates hashed payload | Overlay table; verify uses original JSONB | Redact then verify intact; SQL tamper still detected |
| Archive looks like a chain break | Soft status only; verify reads all rows | Mixed ACTIVE/ARCHIVED verify test |
| Scenario C expands into identity/PDF/filings | Written scope boundary | API matches [SCENARIO_C.md](SCENARIO_C.md) only |
| Canonical form omits a hashed field | Freeze field list in ARCHITECTURE.md | Code review + hash unit tests |

Validation gate for A (assignment script): write → query → verify intact → SQL update one payload → verify names that sequence and `CONTENT_HASH_MISMATCH`.

---

## Assumptions

- Single process, single PostgreSQL; no multi-region replication of the chain.
- SHA-256 and a secret-free chain are enough for **detection**, not for preventing a DBA from rewriting the whole table (they can recompute a new chain; detection assumes the attacker misses at least one stored hash or an off-box copy).
- Payload is JSON objects with dotted paths for redaction; arrays out of scope for v1.
- “Client account access” = `CLIENT_ACCOUNT` + four event types listed in Scenario C.
- No production IAM in the timebox; admin/redact may later use a static API key.

---

## Limitations

- Overlay redaction does not hide plaintext from anyone with SQL access.
- Soft archive never reclaims disk.
- Export recipients cannot recompute per-record `contentHash` when the bundle payload is redacted; they can check `bundleHash` and, if they have service access, `/audit/verify`.
- Compliance report is a live query with a pinned head hash, not a signed, stored filing.
- No rate limiting, no SSO, no multi-tenant isolation, no PDF.

These are deliberate timebox cuts, not accidental omissions. Production follow-ups: column encryption or field-level crypto, partition/tier old rows, signed reports, regulator RBAC.
