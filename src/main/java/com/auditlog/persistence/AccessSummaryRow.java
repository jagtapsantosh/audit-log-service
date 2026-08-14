package com.auditlog.persistence;

import java.time.Instant;

/** JPQL constructor projection for the compliance summary aggregate. */
public record AccessSummaryRow(
        Long totalAccessEvents,
        Long uniqueActors,
        Instant earliestEvent,
        Instant latestEvent
) {}
