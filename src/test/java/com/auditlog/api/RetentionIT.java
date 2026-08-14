package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scenario B1 end to end.
 *
 * <p>A zero-day retention window is used so the sweep has something to do without backdating
 * {@code recorded_at} — which is hashed, and rewriting it would look like tamper rather than like an
 * old record. The scheduled sweep is disabled here so the test drives the cadence itself.
 *
 * <p>The claim being proved is the one the assignment cares about: records can leave normal reads
 * without {@code GET /audit/verify} ever reporting a break.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "audit.retention.days=0",
        "audit.retention.sweep.enabled=false"
})
class RetentionIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @Order(1)
    void seededRecordsStartActiveAndVisible() throws Exception {
        appendEvent("USER_LOGIN", "user-1", "SESSION", "sess-1",
                "2026-08-14T11:30:00Z", "{\"ip\":\"10.0.0.1\"}");
        appendEvent("ACCOUNT_VIEWED", "user-1", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T11:31:00Z", "{\"ip\":\"10.0.0.2\"}");

        JsonNode page = query("");

        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(page.get("content").get(0).has("archivedAt")).isFalse();
        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }

    @Test
    @Order(2)
    void sweepArchivesEverythingPastTheWindow() throws Exception {
        JsonNode result = archive();

        assertThat(result.get("archived").asInt()).isEqualTo(2);
        assertThat(result.get("retentionDays").asInt()).isZero();
        assertThat(result.get("cutoff").asText()).isNotBlank();
    }

    @Test
    @Order(3)
    void archivedRecordsDropOutOfNormalReads() throws Exception {
        assertThat(query("").get("totalElements").asLong()).isZero();
        assertThat(query("?actorId=user-1").get("totalElements").asLong()).isZero();
    }

    @Test
    @Order(4)
    void archivedRecordsAreStillReadableOnRequest() throws Exception {
        JsonNode page = query("?includeArchived=true");

        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        JsonNode first = page.get("content").get(0);
        assertThat(first.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(first.get("archivedAt").asText()).isNotBlank();
        // Archiving touches no hashed field.
        assertThat(first.get("contentHash").asText()).hasSize(64);
    }

    @Test
    @Order(5)
    void verifyStillWalksArchivedRecordsAndStaysIntact() throws Exception {
        JsonNode result = verifyChain();

        assertThat(result.get("intact").asBoolean()).isTrue();
        assertThat(result.get("totalRecords").asLong()).isEqualTo(2);
        assertThat(result.has("firstViolation")).isFalse();
    }

    @Test
    @Order(6)
    void aNewRecordCoexistsWithArchivedOnes() throws Exception {
        appendEvent("USER_LOGOUT", "user-1", "SESSION", "sess-1",
                "2026-08-14T11:40:00Z", "{\"ip\":\"10.0.0.3\"}");

        assertThat(query("").get("totalElements").asLong()).isEqualTo(1);
        assertThat(query("?includeArchived=true").get("totalElements").asLong()).isEqualTo(3);
        // Mixed ACTIVE/ARCHIVED, and the chain links across the boundary.
        JsonNode result = verifyChain();
        assertThat(result.get("intact").asBoolean()).isTrue();
        assertThat(result.get("totalRecords").asLong()).isEqualTo(3);
    }

    @Test
    @Order(7)
    void aRepeatedSweepIsIdempotent() throws Exception {
        assertThat(archive().get("archived").asInt()).isEqualTo(1);
        assertThat(archive().get("archived").asInt()).isZero();
        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }

    @Test
    @Order(8)
    void nothingIsEverDeleted() throws Exception {
        assertThat(query("?includeArchived=true").get("totalElements").asLong()).isEqualTo(3);
        assertThat(verifyChain().get("totalRecords").asLong()).isEqualTo(3);
    }

    @Test
    @Order(9)
    void anIngestApiKeyCannotRunTheSweep() throws Exception {
        mockMvc.perform(post("/audit/admin/archive").header("X-API-Key", INGEST_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @Order(10)
    void aReadOnlyTokenCannotRunTheSweep() throws Exception {
        mockMvc.perform(post("/audit/admin/archive")
                        .header("Authorization", "Bearer " + readerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(11)
    void theSweepWithoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(post("/audit/admin/archive"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @Order(12)
    void anArchivedRecordCanStillBeRedactedWithoutBreakingTheChain() throws Exception {
        long archivedId = query("?includeArchived=true").get("content").get(0).get("id").asLong();

        mockMvc.perform(post("/audit/events/{id}/redact", archivedId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"fieldPaths\":[\"ip\"],\"reason\":\"GDPR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.payload.ip").value("[REDACTED]"))
                .andExpect(jsonPath("$.redactedFields[0]").value("ip"));

        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
        assertThat(query("?includeArchived=true&actorId=user-1")
                .get("content").get(0).get("payload").get("ip").asText())
                .isEqualTo("[REDACTED]");
    }

    private JsonNode archive() throws Exception {
        MvcResult result = mockMvc.perform(post("/audit/admin/archive")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
