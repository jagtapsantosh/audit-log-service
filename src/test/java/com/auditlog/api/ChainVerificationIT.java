package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The assignment's validation script, end to end against a real PostgreSQL: write events, query
 * them, verify the chain, modify a record directly in the data store, then verify again and confirm
 * detection.
 *
 * <p>Deliberately ordered, because the whole point is that each step builds on the previous state.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChainVerificationIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void emptyChainIsIntact() throws Exception {
        JsonNode result = verifyChain();

        assertThat(result.get("intact").asBoolean()).isTrue();
        assertThat(result.get("totalRecords").asLong()).isZero();
        assertThat(result.has("firstViolation")).isFalse();
    }

    @Test
    @Order(2)
    void appendedEventsFormAChainStartingAtGenesis() throws Exception {
        JsonNode first = appendEvent("USER_LOGIN", "user-1", "SESSION", "sess-1",
                "2026-08-14T11:30:00Z", "{\"ip\":\"10.0.0.1\"}");
        JsonNode second = appendEvent("RECORD_UPDATED", "user-1", "CLIENT_ACCOUNT", "acct-9",
                "2026-08-14T11:31:00Z", "{\"field\":\"address\",\"amount\":1.0}");
        JsonNode third = appendEvent("PERMISSION_GRANTED", "admin-2", "CLIENT_ACCOUNT", "acct-9",
                "2026-08-14T11:32:00Z", null);

        assertThat(first.get("sequence").asLong()).isEqualTo(1);
        assertThat(first.get("previousHash").asText()).isEqualTo("0".repeat(64));
        assertThat(first.get("contentHash").asText()).hasSize(64);
        assertThat(second.get("sequence").asLong()).isEqualTo(2);
        assertThat(second.get("previousHash").asText()).isEqualTo(first.get("contentHash").asText());
        assertThat(third.get("previousHash").asText()).isEqualTo(second.get("contentHash").asText());
        // Server-assigned ingest clock is returned alongside the caller's event clock.
        assertThat(third.get("recordedAt").asText()).isNotBlank();
    }

    @Test
    @Order(3)
    void chainIsIntactAfterHonestWrites() throws Exception {
        JsonNode result = verifyChain();

        assertThat(result.get("intact").asBoolean()).isTrue();
        assertThat(result.get("totalRecords").asLong()).isEqualTo(3);
    }

    @Test
    @Order(4)
    void directPayloadUpdateInTheDataStoreIsDetected() throws Exception {
        // The one place this codebase issues an UPDATE against audit_records: simulating the
        // attacker/DBA that the hash chain exists to expose. Mirrors the README psql command.
        jdbcTemplate.update(
                "UPDATE audit_records SET payload = ?::jsonb WHERE sequence_num = 2",
                "{\"tampered\":true}");

        JsonNode result = verifyChain();

        assertThat(result.get("intact").asBoolean()).isFalse();
        assertThat(result.get("totalRecords").asLong()).isEqualTo(3);
        JsonNode violation = result.get("firstViolation");
        assertThat(violation.get("sequence").asLong()).isEqualTo(2);
        assertThat(violation.get("violationType").asText()).isEqualTo("CONTENT_HASH_MISMATCH");
        assertThat(violation.get("expectedHash").asText())
                .isNotEqualTo(violation.get("actualHash").asText());
    }

    @Test
    @Order(5)
    void clockTamperIsAlsoDetected() throws Exception {
        // Both clocks are hashed, so backdating an ingest time after the fact is not a quiet edit.
        jdbcTemplate.update("UPDATE audit_records SET recorded_at = recorded_at - interval '1 day' "
                + "WHERE sequence_num = 1");

        JsonNode violation = verifyChain().get("firstViolation");

        assertThat(violation.get("sequence").asLong()).isEqualTo(1);
        assertThat(violation.get("violationType").asText()).isEqualTo("CONTENT_HASH_MISMATCH");
    }
}
