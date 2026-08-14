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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scenario B2 end to end: redact a sensitive field, confirm readers only ever see {@code [REDACTED]},
 * and confirm the two properties that make this design defensible — the chain still verifies, and a
 * real SQL edit of the same payload is still caught.
 *
 * <p>Ordered, because the tamper step at the end deliberately breaks the chain for good.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedactionIT extends AuditApiIntegrationTest {

    private static final String SENSITIVE_PAYLOAD = """
            {"accountNumber":"1234-5678-9012","customer":{"ssn":"111-22-3333","tier":"gold"},\
            "ip":"10.0.0.1"}""";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    private static long recordId;

    @Test
    @Order(1)
    void appendedEventStartsUnredacted() throws Exception {
        JsonNode event = appendEvent("ACCOUNT_VIEWED", "user-123", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T11:30:00Z", SENSITIVE_PAYLOAD);
        recordId = event.get("id").asLong();

        assertThat(event.get("payload").get("accountNumber").asText()).isEqualTo("1234-5678-9012");
        assertThat(event.get("redactedFields")).isEmpty();
        assertThat(event.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(2)
    void redactionMasksTheFieldAndNamesIt() throws Exception {
        JsonNode redacted = redact(recordId, "{\"fieldPaths\":[\"accountNumber\"],\"reason\":\"GDPR\"}");

        assertThat(redacted.get("payload").get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(redacted.get("payload").get("ip").asText()).isEqualTo("10.0.0.1");
        assertThat(redacted.get("redactedFields")).hasSize(1);
        assertThat(redacted.get("redactedFields").get(0).asText()).isEqualTo("accountNumber");
    }

    @Test
    @Order(3)
    void queryReturnsTheMaskedPayload() throws Exception {
        JsonNode event = query("?actorId=user-123").get("content").get(0);

        assertThat(event.get("payload").get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(event.get("payload").get("customer").get("ssn").asText()).isEqualTo("111-22-3333");
        assertThat(event.get("redactedFields").get(0).asText()).isEqualTo("accountNumber");
    }

    @Test
    @Order(4)
    void chainIsStillIntactAfterRedaction() throws Exception {
        // The point of the whole design: hashes cover the original payload, which is still stored.
        JsonNode result = verifyChain();

        assertThat(result.get("intact").asBoolean()).isTrue();
        assertThat(result.get("totalRecords").asLong()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void nestedPathsCanBeRedacted() throws Exception {
        JsonNode redacted = redact(recordId,
                "{\"fieldPaths\":[\"customer.ssn\"],\"reason\":\"GDPR\"}");

        assertThat(redacted.get("payload").get("customer").get("ssn").asText()).isEqualTo("[REDACTED]");
        assertThat(redacted.get("payload").get("customer").get("tier").asText()).isEqualTo("gold");
        assertThat(redacted.get("redactedFields")).hasSize(2);
        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }

    @Test
    @Order(6)
    void redactingTheSamePathAgainIsANoOp() throws Exception {
        JsonNode redacted = redact(recordId,
                "{\"fieldPaths\":[\"accountNumber\"],\"reason\":\"duplicate request\"}");

        assertThat(redacted.get("redactedFields")).hasSize(2);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_redactions WHERE audit_record_id = ?", Integer.class, recordId);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    @Order(7)
    void theStoredPayloadIsNeverRewritten() {
        String stored = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM audit_records WHERE id = ?", String.class, recordId);

        assertThat(stored).contains("1234-5678-9012").contains("111-22-3333");
    }

    @Test
    @Order(8)
    void theOperatorIsTakenFromTheTokenNotTheBody() {
        String redactedBy = jdbcTemplate.queryForObject(
                "SELECT DISTINCT redacted_by FROM audit_redactions WHERE audit_record_id = ?",
                String.class, recordId);

        assertThat(redactedBy).isEqualTo("ops-admin");
    }

    @Test
    @Order(9)
    void supplyingRedactedByIsRejectedAsAnUnknownField() throws Exception {
        mockMvc.perform(redactRequest(recordId,
                        "{\"fieldPaths\":[\"ip\"],\"reason\":\"GDPR\",\"redactedBy\":\"someone-else\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @Order(10)
    void unknownFieldPathIsRejected() throws Exception {
        mockMvc.perform(redactRequest(recordId, "{\"fieldPaths\":[\"noSuchField\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD_PATH"));
    }

    @Test
    @Order(11)
    void arrayIndexPathsAreRejected() throws Exception {
        mockMvc.perform(redactRequest(recordId, "{\"fieldPaths\":[\"items[0].id\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_PATH"));
    }

    @Test
    @Order(12)
    void emptyFieldPathsIsRejected() throws Exception {
        mockMvc.perform(redactRequest(recordId, "{\"fieldPaths\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @Order(13)
    void redactingAnUnknownRecordIsNotFound() throws Exception {
        mockMvc.perform(redactRequest(999_999L, "{\"fieldPaths\":[\"ip\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    @Test
    @Order(14)
    void anIngestApiKeyCannotRedact() throws Exception {
        mockMvc.perform(post("/audit/events/{id}/redact", recordId)
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldPaths\":[\"ip\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @Order(15)
    void aReadOnlyTokenCannotRedact() throws Exception {
        mockMvc.perform(post("/audit/events/{id}/redact", recordId)
                        .header("Authorization", "Bearer " + readerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldPaths\":[\"ip\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(16)
    void redactionWithoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(post("/audit/events/{id}/redact", recordId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldPaths\":[\"ip\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(17)
    void aDirectSqlEditOfTheRedactedPayloadIsStillDetected() throws Exception {
        // Redaction must not become a way to launder a tampered payload: the hash still covers the
        // original bytes, so rewriting them is caught exactly as it was in Scenario A.
        jdbcTemplate.update("UPDATE audit_records SET payload = ?::jsonb WHERE id = ?",
                "{\"accountNumber\":\"0000-0000-0000\"}", recordId);

        JsonNode result = verifyChain();

        assertThat(result.get("intact").asBoolean()).isFalse();
        assertThat(result.get("firstViolation").get("violationType").asText())
                .isEqualTo("CONTENT_HASH_MISMATCH");
    }

    private JsonNode redact(long id, String body) throws Exception {
        MvcResult result = mockMvc.perform(redactRequest(id, body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder redactRequest(long id, String body) throws Exception {
        return post("/audit/events/{id}/redact", id)
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
