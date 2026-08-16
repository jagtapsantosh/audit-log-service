package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TailTruncationIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void deletingTheNewestRecordIsDetected() throws Exception {
        appendEvent("USER_LOGIN", "user-1", "SESSION", "sess-1",
                "2026-08-14T11:30:00Z", "{\"ip\":\"10.0.0.1\"}");
        appendEvent("USER_LOGIN", "user-1", "SESSION", "sess-2",
                "2026-08-14T11:31:00Z", "{\"ip\":\"10.0.0.2\"}");
        appendEvent("USER_LOGIN", "user-1", "SESSION", "sess-3",
                "2026-08-14T11:32:00Z", "{\"ip\":\"10.0.0.3\"}");

        assertThat(verifyChain().get("intact").asBoolean()).isTrue();

        jdbcTemplate.update("DELETE FROM audit_records WHERE sequence_num = 3");

        JsonNode result = verifyChain();
        assertThat(result.get("intact").asBoolean()).isFalse();
        assertThat(result.get("totalRecords").asLong()).isEqualTo(2);
        assertThat(result.get("firstViolation").get("violationType").asText()).isEqualTo("TAIL_TRUNCATION");
        assertThat(result.get("firstViolation").get("sequence").asLong()).isEqualTo(3);
    }
}
