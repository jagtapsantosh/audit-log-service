package com.auditlog.domain;

import java.util.List;

/**
 * A request to redact field paths on one record.
 *
 * <p>{@code redactedBy} is taken from the caller's authenticated identity, never from the request
 * body, so an operator id cannot be spoofed in the privacy audit trail.
 */
public record RedactionCommand(
        long recordId,
        List<String> fieldPaths,
        String reason,
        String redactedBy
) {}
