# Scenario C — Compliance Reporting

Intentionally under-specified. This file is the clarification **before** code. Implementation follows only the statement in [Clarified requirement](#clarified-requirement).

---

## Original statement

> Regulators need to be able to audit access to client account data.

---

## Ambiguities

| # | Ambiguity | Question for product | Assumption used |
|---|-----------|----------------------|-----------------|
| 1 | What is “access”? | Login? View? Update? Export? Permission change? | Closed set: `ACCOUNT_VIEWED`, `ACCOUNT_UPDATED`, `STATEMENT_DOWNLOADED`, `PERMISSION_GRANTED` |
| 2 | What is “client account data”? | Any resource? Only accounts? PII fields? | `resourceType = CLIENT_ACCOUNT` |
| 3 | Who is the regulator user? | SSO? Separate IdP? Break-glass? | No regulator identity in this prototype (production gap) |
| 4 | Report shape | PDF filing? CSV? Portal UI? | JSON API + CSV/JSON file download |
| 5 | Freshness | Live query vs snapshot for an exam date | Point-in-time snapshot: `generatedAt` + `chainHeadHash` |
| 6 | Completeness vs redaction | Must regulators see account numbers? | Same redaction overlay as B; metadata lists redacted fields |
| 7 | Tenancy | One bank vs many | Single-tenant prototype |
| 8 | Retention vs exams | Archived access events in or out? | Included; exams need history. Flag `status` on each event |

---

## Clarified requirement

> Provide a read-only compliance report and export that lists all audit events where `resourceType = CLIENT_ACCOUNT` and `eventType` is one of `ACCOUNT_VIEWED`, `ACCOUNT_UPDATED`, `STATEMENT_DOWNLOADED`, `PERMISSION_GRANTED`, filterable by account (`resourceId`), actor (`actorId`), and time range (`from` / `to` on **`occurredAt`**), with immutable evidence that the report data matches the tamper-evident audit chain at report generation time (`chainHeadHash` = latest `contentHash` when the report was built).

This is the only C requirement the code is accountable to.

---

## Design

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/audit/compliance/access-report` | Paginated JSON report + summary |
| `GET` | `/audit/compliance/access-report/export` | Same filter; `format=csv` or `json` (default json) |

Query params: `resourceId`, `actorId`, `from`, `to`, `page`, `size`. `from`/`to` are inclusive on **`occurredAt`**. Always constrained to `CLIENT_ACCOUNT` + the access event-type set (not caller-overridable). Include archived rows.

Response:

```json
{
  "reportId": "uuid",
  "generatedAt": "2026-08-16T12:00:00Z",
  "chainHeadHash": "<contentHash of max sequence_num at generation>",
  "filter": {
    "resourceType": "CLIENT_ACCOUNT",
    "eventTypes": ["ACCOUNT_VIEWED", "ACCOUNT_UPDATED", "STATEMENT_DOWNLOADED", "PERMISSION_GRANTED"],
    "resourceId": "acct-99",
    "actorId": null,
    "from": null,
    "to": null
  },
  "summary": {
    "totalAccessEvents": 12,
    "uniqueActors": 3,
    "earliestEvent": "...",
    "latestEvent": "..."
  },
  "events": [ ],
  "verificationHint": "GET /audit/verify must be intact. chainHeadHash must equal contentHash of the current chain head if no writes occurred after generatedAt."
}
```

Events in the report are redaction-aware (Scenario B view). `reportId` is a UUID generated at request time (not a stored report table in v1).

**Evidence model:** the report does not re-hash the universe. It pins `chainHeadHash` so a reviewer can (1) verify the live chain, (2) confirm the head hash at `generatedAt` if they export a bundle or re-query immediately. If new events arrive after generation, head hash will differ — that is expected; the report is a snapshot.

---

## In scope vs scoped out

| In this prototype | Out (production next steps) | Why out |
|-------------------|-----------------------------|---------|
| Read-only report + CSV/JSON export | Regulator SSO / RBAC | Identity provider not in assignment; would fake security |
| Fixed access event-type enum | Configurable product catalog of “access” | Needs a real product owner; enum is an explicit assumption |
| `chainHeadHash` snapshot | Cryptographic signed report file (JWS) | Timebox; head hash is enough to cross-check |
| Redaction-aware rows | Unredacted “exam mode” | Privacy vs exam completeness needs legal input |
| | Scheduled regulatory filing / PDF | Different product |
| | Multi-tenant isolation | Single DB in prototype |
| | Immutable stored report archive | Can add `compliance_reports` table later |

Partial implementation is acceptable only if this file still matches the running API. Do not implement SSO “for show.”

---

## Decomposition

| # | Task | Acceptance |
|---|------|------------|
| C1 | Freeze this clarification (this file) before coding | Reviewers can diff API vs this statement |
| C2 | `ComplianceReportService` filter + summary + head hash | Non-access and non-`CLIENT_ACCOUNT` events excluded |
| C3 | JSON report endpoint | `reportId`, `generatedAt`, `chainHeadHash` always present |
| C4 | CSV/JSON export | Same filter; CSV columns documented in OpenAPI |
| C5 | Tests | Seed mix of access and non-access; assert counts; assert head hash equals max sequence hash |

---

## Questions still worth asking (not blocking)

- Should `PERMISSION_GRANTED` on a non-account resource ever appear? (We say no unless `CLIENT_ACCOUNT`.)
- Legal hold: skip retention archive for rows that appear in an issued report? (Not implemented.)
- Do regulators need a watermarked PDF? (No.)
