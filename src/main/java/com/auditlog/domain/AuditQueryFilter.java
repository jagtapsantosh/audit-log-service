package com.auditlog.domain;

import java.time.Instant;
import java.util.List;

/**
 * Query filters. Every field is optional and any combination is allowed. {@code occurredFrom} /
 * {@code occurredTo} bound the client event clock; {@code recordedFrom} / {@code recordedTo} bound
 * the server ingest clock. All bounds are inclusive.
 *
 * <p>{@code includeArchived} defaults to false: records past the retention window drop out of normal
 * reads without ever leaving the chain.
 */
public record AuditQueryFilter(
        String actorId,
        String resourceType,
        String resourceId,
        String eventType,
        Instant occurredFrom,
        Instant occurredTo,
        Instant recordedFrom,
        Instant recordedTo,
        boolean includeArchived
) {

    /** Filters without the retention flag, which keeps the Scenario A call sites unchanged. */
    public AuditQueryFilter(
            String actorId,
            String resourceType,
            String resourceId,
            String eventType,
            Instant occurredFrom,
            Instant occurredTo,
            Instant recordedFrom,
            Instant recordedTo
    ) {
        this(actorId, resourceType, resourceId, eventType, occurredFrom, occurredTo, recordedFrom,
                recordedTo, false);
    }

    public static AuditQueryFilter empty() {
        return new AuditQueryFilter(null, null, null, null, null, null, null, null, false);
    }

    /** Statuses this filter admits, as the persistence layer needs them. */
    public List<RecordStatus> statuses() {
        return includeArchived
                ? List.of(RecordStatus.ACTIVE, RecordStatus.ARCHIVED)
                : List.of(RecordStatus.ACTIVE);
    }
}
