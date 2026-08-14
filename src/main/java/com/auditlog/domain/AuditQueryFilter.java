package com.auditlog.domain;

import java.time.Instant;

/**
 * Query filters. Every field is optional and any combination is allowed. {@code occurredFrom} /
 * {@code occurredTo} bound the client event clock; {@code recordedFrom} / {@code recordedTo} bound
 * the server ingest clock. All bounds are inclusive.
 */
public record AuditQueryFilter(
        String actorId,
        String resourceType,
        String resourceId,
        String eventType,
        Instant occurredFrom,
        Instant occurredTo,
        Instant recordedFrom,
        Instant recordedTo
) {

    public static AuditQueryFilter empty() {
        return new AuditQueryFilter(null, null, null, null, null, null, null, null);
    }
}
