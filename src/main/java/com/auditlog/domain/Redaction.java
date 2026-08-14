package com.auditlog.domain;

import java.time.Instant;

/**
 * One redacted field path on one record. This is the whole redaction mechanism: an overlay row that
 * read APIs apply, never an edit to the stored payload.
 *
 * <p>{@code id} is null before persistence assigns one.
 */
public record Redaction(
        Long id,
        long auditRecordId,
        String fieldPath,
        Instant redactedAt,
        String redactedBy,
        String reason
) {

    public static Redaction pending(long auditRecordId, String fieldPath, Instant redactedAt,
                                    String redactedBy, String reason) {
        return new Redaction(null, auditRecordId, fieldPath, redactedAt, redactedBy, reason);
    }
}
