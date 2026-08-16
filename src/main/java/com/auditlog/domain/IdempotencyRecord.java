package com.auditlog.domain;

import java.time.Instant;

public record IdempotencyRecord(
        String clientId,
        String key,
        String requestHash,
        long auditRecordId,
        Instant createdAt
) {
}
