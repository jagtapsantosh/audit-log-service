package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * A caller-supplied event before the service assigns chain position, ingest time, and hashes.
 * Callers cannot influence {@code sequence}, {@code recordedAt}, or either hash.
 */
public record NewAuditEvent(
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        JsonNode payload,
        Instant occurredAt
) {
}
