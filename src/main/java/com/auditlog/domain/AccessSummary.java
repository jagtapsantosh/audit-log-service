package com.auditlog.domain;

import java.time.Instant;

/**
 * Totals over the <em>whole</em> matching set, not the current page. Earliest/latest are null when
 * nothing matched.
 */
public record AccessSummary(
        long totalAccessEvents,
        long uniqueActors,
        Instant earliestEvent,
        Instant latestEvent
) {

    public static AccessSummary empty() {
        return new AccessSummary(0, 0, null, null);
    }
}
