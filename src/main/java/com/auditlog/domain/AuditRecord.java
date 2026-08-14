package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * An immutable audit record as the domain sees it. {@code id} is null before persistence assigns
 * one; every other field is fixed at append time and never rewritten.
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
        String previousHash
) {

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
