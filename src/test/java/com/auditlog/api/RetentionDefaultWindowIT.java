package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The default retention window is 365 days against {@code recordedAt}. A just-written record must
 * stay ACTIVE, otherwise a demo would look like the log ate its own writes.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "audit.retention.sweep.enabled=false")
class RetentionDefaultWindowIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void aFreshRecordIsNotArchivedByTheDefaultWindow() throws Exception {
        JsonNode written = appendEvent("USER_LOGIN", "user-fresh", "SESSION", "sess-fresh",
                "2020-01-01T00:00:00Z", "{\"ip\":\"10.0.0.1\"}");

        MvcResult sweep = mockMvc.perform(post("/audit/admin/archive")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode result = objectMapper.readTree(sweep.getResponse().getContentAsString());

        assertThat(result.get("archived").asInt()).isZero();
        assertThat(result.get("retentionDays").asInt()).isEqualTo(365);
        assertThat(query("").get("totalElements").asLong()).isEqualTo(1);
        assertThat(query("").get("content").get(0).get("id").asLong()).isEqualTo(written.get("id").asLong());
        assertThat(query("").get("content").get(0).get("status").asText()).isEqualTo("ACTIVE");
        // A six-year-old occurredAt did not keep (or hide) the row: retention keys off ingest time.
        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }
}
