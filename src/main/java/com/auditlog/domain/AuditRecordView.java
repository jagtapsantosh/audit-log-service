package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * What a reader is allowed to see: the record as stored, plus the payload with redacted paths masked
 * and the list of paths that were masked.
 *
 * <p>Verification never uses this type — it always re-hashes {@code record.payload()}, the original
 * bytes.
 */
public record AuditRecordView(AuditRecord record, JsonNode visiblePayload, List<String> redactedFields) {

    /** A record with no redactions: the visible payload is the stored payload. */
    public static AuditRecordView of(AuditRecord record) {
        return new AuditRecordView(record, record.payload(), List.of());
    }
}
