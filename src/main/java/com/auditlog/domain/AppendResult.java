package com.auditlog.domain;

/** Outcome of a write. {@code replay} is true when an {@code Idempotency-Key} returned a prior row. */
public record AppendResult(AuditRecord record, boolean replay) {

    public static AppendResult created(AuditRecord record) {
        return new AppendResult(record, false);
    }

    public static AppendResult replayed(AuditRecord record) {
        return new AppendResult(record, true);
    }
}
