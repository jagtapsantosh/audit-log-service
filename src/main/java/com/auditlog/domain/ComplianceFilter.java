package com.auditlog.domain;

import java.time.Instant;
import java.util.List;

/**
 * The filter as it appears in the report. Always includes the frozen resource type and event-type
 * set, so a recipient can see exactly what "access to client account data" meant when the snapshot
 * was taken.
 */
public record ComplianceFilter(
        String resourceType,
        List<String> eventTypes,
        String resourceId,
        String actorId,
        Instant from,
        Instant to
) {

    public static ComplianceFilter of(ComplianceAccessFilter filter) {
        return new ComplianceFilter(
                AccessScope.RESOURCE_TYPE,
                AccessScope.EVENT_TYPES,
                filter.resourceId(),
                filter.actorId(),
                filter.occurredFrom(),
                filter.occurredTo());
    }
}
