package com.auditlog.domain;

import java.time.Instant;

/**
 * Optional regulator-supplied constraints on top of {@link AccessScope}. {@code from}/{@code to}
 * bound {@code occurredAt} (event time), inclusive. Blank strings are treated as absent.
 */
public record ComplianceAccessFilter(
        String actorId,
        String resourceId,
        Instant occurredFrom,
        Instant occurredTo
) {

    public static ComplianceAccessFilter empty() {
        return new ComplianceAccessFilter(null, null, null, null);
    }

    public ComplianceAccessFilter {
        actorId = blankToNull(actorId);
        resourceId = blankToNull(resourceId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
