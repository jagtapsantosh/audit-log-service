package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * The exact field set covered by {@code contentHash}. Anything outside this record (surrogate id,
 * archive status, redaction overlays) is deliberately not hashed.
 */
public record ChainInput(
        long sequence,
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        JsonNode payload,
        Instant occurredAt,
        Instant recordedAt,
        String previousHash
) {
}
