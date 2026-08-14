package com.auditlog.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * One record as it appears in an export bundle: the server's own hashes copied verbatim, the payload
 * with redactions applied, and the list of paths that were masked.
 *
 * <p>{@code previousHash} frequently points at a record that is <em>not</em> in the bundle, because
 * a filtered export is a sparse slice of the global chain. That is expected and is why a recipient
 * checks {@code bundleHash} rather than trying to replay the chain.
 */
public record ExportRecord(
        long sequence,
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        Instant occurredAt,
        Instant recordedAt,
        String contentHash,
        String previousHash,
        JsonNode payload,
        List<String> redactedFields
) {

    public static ExportRecord from(AuditRecordView view) {
        AuditRecord record = view.record();
        return new ExportRecord(
                record.sequence(),
                record.eventType(),
                record.actorId(),
                record.resourceType(),
                record.resourceId(),
                record.occurredAt(),
                record.recordedAt(),
                record.contentHash(),
                record.previousHash(),
                view.visiblePayload(),
                view.redactedFields());
    }

    /** {@code @JsonIgnore} because the bundle format is fixed and covered by {@code bundleHash}. */
    @JsonIgnore
    public boolean isRedacted() {
        return redactedFields != null && !redactedFields.isEmpty();
    }
}
