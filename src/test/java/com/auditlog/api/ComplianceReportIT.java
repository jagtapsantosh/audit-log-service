package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auditlog.domain.AccessScope;
import com.auditlog.domain.ComplianceCsv;
import com.auditlog.domain.HashChainService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scenario C end to end: the report lists only CLIENT_ACCOUNT access events, pins the live chain
 * head (which may not itself be an access event), applies the redaction overlay, and includes
 * archived rows.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "audit.retention.days=0",
        "audit.retention.sweep.enabled=false"
})
class ComplianceReportIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static long sensitiveRecordId;
    private static String lastAccessHash;
    private static String chainHeadHash;

    @Test
    @Order(1)
    void seedMixOfAccessAndNonAccess() throws Exception {
        appendEvent("USER_LOGIN", "user-1", "SESSION", "sess-1",
                "2026-08-14T09:00:00Z", "{\"ip\":\"10.0.0.1\"}");
        JsonNode viewed = appendEvent("ACCOUNT_VIEWED", "user-1", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T10:00:00Z",
                "{\"accountNumber\":\"1234-5678-9012\",\"ip\":\"10.0.0.1\"}");
        sensitiveRecordId = viewed.get("id").asLong();
        appendEvent("ACCOUNT_UPDATED", "user-2", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T11:00:00Z", "{\"field\":\"address\"}");
        appendEvent("STATEMENT_DOWNLOADED", "user-1", "CLIENT_ACCOUNT", "acct-2",
                "2026-08-14T12:00:00Z", "{\"period\":\"2026-Q2\"}");
        JsonNode granted = appendEvent("PERMISSION_GRANTED", "admin-1", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T13:00:00Z", "{\"role\":\"viewer\"}");
        lastAccessHash = granted.get("contentHash").asText();
        appendEvent("PERMISSION_GRANTED", "admin-1", "ROLE", "role-ops",
                "2026-08-14T14:00:00Z", "{\"role\":\"ops\"}");
        appendEvent("RECORD_UPDATED", "user-1", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T15:00:00Z", "{\"field\":\"nickname\"}");
        JsonNode laterLogin = appendEvent("USER_LOGOUT", "user-9", "SESSION", "sess-9",
                "2026-08-14T16:00:00Z", null);
        chainHeadHash = laterLogin.get("contentHash").asText();

        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
        assertThat(chainHeadHash).isNotEqualTo(lastAccessHash);
    }

    @Test
    @Order(2)
    void reportExcludesNonAccessAndNonAccountEvents() throws Exception {
        JsonNode report = accessReport("");

        assertThat(UUID.fromString(report.get("reportId").asText())).isNotNull();
        assertThat(report.get("chainHeadHash").asText()).isEqualTo(chainHeadHash);
        assertThat(report.get("filter").get("resourceType").asText()).isEqualTo("CLIENT_ACCOUNT");
        assertThat(eventTypes(report.get("filter").get("eventTypes")))
                .containsExactlyElementsOf(AccessScope.EVENT_TYPES);
        assertThat(report.get("verificationHint").asText()).isEqualTo(AccessScope.VERIFICATION_HINT);

        assertThat(report.get("summary").get("totalAccessEvents").asInt()).isEqualTo(4);
        assertThat(report.get("summary").get("uniqueActors").asInt()).isEqualTo(3);
        assertThat(report.get("summary").get("earliestEvent").asText())
                .startsWith("2026-08-14T10:00:00");
        assertThat(report.get("summary").get("latestEvent").asText())
                .startsWith("2026-08-14T13:00:00");

        assertThat(sequences(report)).containsExactly(2L, 3L, 4L, 5L);
        assertThat(eventTypes(report.get("events")))
                .containsExactly("ACCOUNT_VIEWED", "ACCOUNT_UPDATED", "STATEMENT_DOWNLOADED",
                        "PERMISSION_GRANTED");
        assertThat(report.get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    @Order(3)
    void filtersByAccountActorAndOccurredAt() throws Exception {
        JsonNode byAccount = accessReport("?resourceId=acct-1");
        assertThat(sequences(byAccount)).containsExactly(2L, 3L, 5L);
        assertThat(byAccount.get("summary").get("totalAccessEvents").asInt()).isEqualTo(3);

        JsonNode byActor = accessReport("?actorId=user-1");
        assertThat(sequences(byActor)).containsExactly(2L, 4L);

        JsonNode byTime = accessReport("?from=2026-08-14T11:00:00Z&to=2026-08-14T12:00:00Z");
        assertThat(sequences(byTime)).containsExactly(3L, 4L);
    }

    @Test
    @Order(4)
    void callerCannotWidenTheScopeWithQueryParams() throws Exception {
        // eventType / resourceType are not parameters; noise is ignored and the frozen set still applies.
        JsonNode report = accessReport("?eventType=USER_LOGIN&resourceType=SESSION");

        assertThat(sequences(report)).containsExactly(2L, 3L, 4L, 5L);
        assertThat(report.get("filter").get("resourceType").asText()).isEqualTo("CLIENT_ACCOUNT");
    }

    @Test
    @Order(5)
    void anInvertedRangeIsRejected() throws Exception {
        mockMvc.perform(get("/audit/compliance/access-report"
                        + "?from=2026-08-14T12:00:00Z&to=2026-08-14T11:00:00Z")
                        .header("Authorization", "Bearer " + regulatorToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }

    @Test
    @Order(6)
    void redactedFieldsAreMaskedInTheReport() throws Exception {
        mockMvc.perform(post("/audit/events/{id}/redact", sensitiveRecordId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldPaths\":[\"accountNumber\"],\"reason\":\"GDPR\"}"))
                .andExpect(status().isOk());

        JsonNode viewed = accessReport("?resourceId=acct-1").get("events").get(0);
        assertThat(viewed.get("payload").get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(viewed.get("payload").get("ip").asText()).isEqualTo("10.0.0.1");
        assertThat(viewed.get("redactedFields").get(0).asText()).isEqualTo("accountNumber");
        assertThat(accessReport("").get("chainHeadHash").asText()).isEqualTo(chainHeadHash);
        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }

    @Test
    @Order(7)
    void jsonExportIsTheFullSnapshotAsAnAttachment() throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/compliance/access-report/export")
                        .header("Authorization", "Bearer " + regulatorToken()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "access-report-");
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(sequences(body)).containsExactly(2L, 3L, 4L, 5L);
        assertThat(body.get("events").get(0).get("payload").get("accountNumber").asText())
                .isEqualTo("[REDACTED]");
        assertThat(body.get("chainHeadHash").asText()).isEqualTo(chainHeadHash);
    }

    @Test
    @Order(8)
    void csvExportHasTheDocumentedColumnsAndMasksRedactions() throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/compliance/access-report/export?format=csv")
                        .header("Authorization", "Bearer " + regulatorToken()))
                .andExpect(status().isOk())
                .andReturn();

        String csv = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv).startsWith(ComplianceCsv.HEADER);
        assertThat(csv).contains("ACCOUNT_VIEWED").contains("[REDACTED]").doesNotContain("1234-5678-9012");
        assertThat(csv.lines().count()).isEqualTo(5); // header + 4 events
    }

    @Test
    @Order(9)
    void unknownExportFormatIsRejected() throws Exception {
        mockMvc.perform(get("/audit/compliance/access-report/export?format=pdf")
                        .header("Authorization", "Bearer " + regulatorToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FORMAT"));
    }

    @Test
    @Order(10)
    void archivedAccessEventsStayInTheReport() throws Exception {
        mockMvc.perform(post("/audit/admin/archive")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        JsonNode report = accessReport("");
        assertThat(report.get("summary").get("totalAccessEvents").asInt()).isEqualTo(4);
        assertThat(report.get("events").get(0).get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(query("").get("totalElements").asLong()).isZero();
        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }

    @Test
    @Order(11)
    void anIngestApiKeyCannotReadTheReport() throws Exception {
        mockMvc.perform(get("/audit/compliance/access-report").header("X-API-Key", INGEST_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @Order(12)
    void anOpsAdminTokenCannotReadTheReport() throws Exception {
        mockMvc.perform(get("/audit/compliance/access-report")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(13)
    void missingCredentialsAreUnauthorized() throws Exception {
        mockMvc.perform(get("/audit/compliance/access-report"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(14)
    void emptyMatchSetStillPinsTheLiveHead() throws Exception {
        JsonNode report = accessReport("?actorId=nobody-at-all");

        assertThat(report.get("summary").get("totalAccessEvents").asInt()).isZero();
        assertThat(report.get("events")).isEmpty();
        assertThat(report.get("chainHeadHash").asText()).isEqualTo(chainHeadHash);
        assertThat(report.get("chainHeadHash").asText()).isNotEqualTo(HashChainService.GENESIS_HASH);
    }

    private JsonNode accessReport(String queryString) throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/compliance/access-report" + queryString)
                        .header("Authorization", "Bearer " + regulatorToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<Long> sequences(JsonNode report) {
        List<Long> sequences = new ArrayList<>();
        report.get("events").forEach(event -> sequences.add(event.get("sequence").asLong()));
        return sequences;
    }

    private static List<String> eventTypes(JsonNode array) {
        List<String> types = new ArrayList<>();
        array.forEach(node -> types.add(node.isTextual() ? node.asText() : node.get("eventType").asText()));
        return types;
    }
}
