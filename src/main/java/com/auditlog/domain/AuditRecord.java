package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * An immutable audit record as the domain sees it. {@code id} is null before persistence assigns
 * one; every hashed field is fixed at append time and never rewritten.
 *
 * <p>{@code status}, {@code archivedAt}, and {@code hasRedactions} are retention and privacy
 * metadata added in Scenario B. They sit outside {@link #chainInput()} on purpose: archiving or
 * redacting a record must not change what its {@code contentHash} covers.
 */
public record AuditRecord(
        Long id,
        long sequence,
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        JsonNode payload,
        Instant occurredAt,
        Instant recordedAt,
        String contentHash,
        String previousHash,
        RecordStatus status,
        Instant archivedAt,
        boolean hasRedactions
) {

    /** A newly appended record: active, never archived, nothing redacted yet. */
    public AuditRecord(
            Long id,
            long sequence,
            String eventType,
            String actorId,
            String resourceType,
            String resourceId,
            JsonNode payload,
            Instant occurredAt,
            Instant recordedAt,
            String contentHash,
            String previousHash
    ) {
        this(id, sequence, eventType, actorId, resourceType, resourceId, payload, occurredAt,
                recordedAt, contentHash, previousHash, RecordStatus.ACTIVE, null, false);
    }

    public boolean isArchived() {
        return status == RecordStatus.ARCHIVED;
    }

    public ChainInput chainInput() {
        return new ChainInput(
                sequence,
                eventType,
                actorId,
                resourceType,
                resourceId,
                payload,
                occurredAt,
                recordedAt,
                previousHash);
    }
}
