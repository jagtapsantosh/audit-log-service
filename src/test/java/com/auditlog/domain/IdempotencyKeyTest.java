package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    @Test
    void omittedHeaderMeansNoKey() {
        assertThat(IdempotencyKey.parse("ingest-service", null)).isNull();
    }

    @Test
    void acceptsASimpleToken() {
        IdempotencyKey key = IdempotencyKey.parse("ingest-service", "retry-1");
        assertThat(key.clientId()).isEqualTo("ingest-service");
        assertThat(key.key()).isEqualTo("retry-1");
    }

    @Test
    void rejectsBlankOrIllegalCharacters() {
        assertThatThrownBy(() -> IdempotencyKey.parse("ingest-service", " "))
                .isInstanceOf(InvalidAuditEventException.class)
                .extracting(ex -> ((InvalidAuditEventException) ex).code())
                .isEqualTo("INVALID_IDEMPOTENCY_KEY");
        assertThatThrownBy(() -> IdempotencyKey.parse("ingest-service", "has spaces"))
                .isInstanceOf(InvalidAuditEventException.class);
    }

    @Test
    void rejectsAKeyWithoutAClient() {
        assertThatThrownBy(() -> IdempotencyKey.parse(" ", "retry-1"))
                .isInstanceOf(InvalidAuditEventException.class)
                .extracting(ex -> ((InvalidAuditEventException) ex).code())
                .isEqualTo("IDEMPOTENCY_CLIENT_REQUIRED");
    }
}
