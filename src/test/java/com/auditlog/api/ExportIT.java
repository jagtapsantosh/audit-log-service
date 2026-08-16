package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auditlog.domain.ExportVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
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
 * Scenario B3 end to end, from the recipient's side: download a bundle and hand it to the standalone
 * {@link ExportVerifier} with no access to the service.
 *
 * <p>The subject's records are interleaved with another actor's, so the exported sequence numbers are
 * genuinely sparse — the case a naive "re-walk the chain" verifier would wrongly reject.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "audit.retention.days=0",
        "audit.retention.sweep.enabled=false"
})
class ExportIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final ExportVerifier verifier = new ExportVerifier();

    private static final List<Long> subjectRecordIds = new ArrayList<>();

    @Test
    @Order(1)
    void seedInterleavedEvents() throws Exception {
        subjectRecordIds.add(appendEvent("ACCOUNT_VIEWED", "user-123", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T11:30:00Z", "{\"accountNumber\":\"1234-5678\",\"ip\":\"10.0.0.1\"}")
                .get("id").asLong());
        appendEvent("USER_LOGIN", "user-999", "SESSION", "sess-9",
                "2026-08-14T11:31:00Z", "{\"ip\":\"10.0.0.9\"}");
        subjectRecordIds.add(appendEvent("ACCOUNT_UPDATED", "user-123", "CLIENT_ACCOUNT", "acct-1",
                "2026-08-14T11:32:00Z", "{\"field\":\"address\",\"amount\":1.50}")
                .get("id").asLong());
        appendEvent("USER_LOGOUT", "user-999", "SESSION", "sess-9",
                "2026-08-14T11:33:00Z", null);
        subjectRecordIds.add(appendEvent("ACCOUNT_VIEWED", "user-123", "CLIENT_ACCOUNT", "acct-2",
                "2026-08-14T11:34:00Z", "{\"ip\":\"10.0.0.1\"}")
                .get("id").asLong());

        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }

    @Test
    @Order(2)
    void exportsASparseSliceForOneActor() throws Exception {
        MvcResult result = exportRequest("?actorId=user-123");
        JsonNode bundle = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "audit-export-user-123.json");
        assertThat(bundle.get("exportVersion").asText()).isEqualTo("1.0");
        assertThat(bundle.get("genesisHash").asText()).isEqualTo("0".repeat(64));
        assertThat(bundle.get("filter").get("actorId").asText()).isEqualTo("user-123");
        assertThat(bundle.get("filter").has("resourceId")).isFalse();
        assertThat(bundle.get("bundleHash").asText()).hasSize(64);
        // Sequences 1, 3, 5: the even ones belong to user-999.
        assertThat(sequences(bundle)).containsExactly(1L, 3L, 5L);
        // The served document is the published contract, so nothing extra may appear in it.
        assertThat(fieldNames(bundle)).containsExactlyInAnyOrder(
                "exportVersion", "exportedAt", "filter", "genesisHash", "records", "bundleHash",
                "bundleSignature");
        assertThat(bundle.get("bundleSignature").asText()).hasSize(64);
        assertThat(fieldNames(bundle.get("records").get(0))).containsExactlyInAnyOrder(
                "sequence", "eventType", "actorId", "resourceType", "resourceId", "occurredAt",
                "recordedAt", "contentHash", "previousHash", "payload", "redactedFields");
    }

    @Test
    @Order(3)
    void theRecipientCanVerifyTheBundleWithGapsAndNoServer() throws Exception {
        ExportVerifier.Report report = verifier.verify(exportBody("?actorId=user-123"));

        assertThat(report.intact()).isTrue();
        assertThat(report.bundleHashValid()).isTrue();
        assertThat(report.recordsRehashed()).isEqualTo(3);
        assertThat(report.recordsSkippedBecauseRedacted()).isZero();
        assertThat(report.findings()).isEmpty();
    }

    @Test
    @Order(4)
    void editingTheDownloadedFileIsDetected() throws Exception {
        ObjectNode bundle = (ObjectNode) objectMapper.readTree(exportBody("?actorId=user-123"));
        ((ObjectNode) bundle.get("records").get(0).get("payload")).put("ip", "10.9.9.9");

        ExportVerifier.Report report = verifier.verify(bundle);

        assertThat(report.intact()).isFalse();
        assertThat(report.findings()).anyMatch(finding -> finding.startsWith("bundleHash mismatch"));
    }

    @Test
    @Order(5)
    void redactedRecordsAreMaskedInTheBundleAndSkippedByTheVerifier() throws Exception {
        mockMvc.perform(post("/audit/events/{id}/redact", subjectRecordIds.getFirst())
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldPaths\":[\"accountNumber\"],\"reason\":\"GDPR\"}"))
                .andExpect(status().isOk());

        JsonNode bundle = objectMapper.readTree(exportBody("?actorId=user-123"));
        JsonNode redactedRecord = bundle.get("records").get(0);

        assertThat(redactedRecord.get("payload").get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(redactedRecord.get("redactedFields").get(0).asText()).isEqualTo("accountNumber");
        // The server's hash of the original payload is still copied verbatim.
        assertThat(redactedRecord.get("contentHash").asText()).hasSize(64);

        ExportVerifier.Report report = verifier.verify(bundle);
        assertThat(report.intact()).isTrue();
        assertThat(report.recordsRehashed()).isEqualTo(2);
        assertThat(report.recordsSkippedBecauseRedacted()).isEqualTo(1);
    }

    @Test
    @Order(6)
    void exportsByResource() throws Exception {
        JsonNode bundle = objectMapper.readTree(
                exportBody("?resourceType=CLIENT_ACCOUNT&resourceId=acct-1"));

        assertThat(sequences(bundle)).containsExactly(1L, 3L);
        assertThat(bundle.get("filter").get("resourceType").asText()).isEqualTo("CLIENT_ACCOUNT");
        assertThat(verifier.verify(bundle).intact()).isTrue();
    }

    @Test
    @Order(7)
    void anExportWithNoSubjectIsRejected() throws Exception {
        mockMvc.perform(get("/audit/export").header("X-API-Key", INGEST_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_SUBJECT_REQUIRED"));

        // A resourceType on its own is a filter, not a subject.
        mockMvc.perform(get("/audit/export?resourceType=SESSION").header("X-API-Key", INGEST_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPORT_SUBJECT_REQUIRED"));
    }

    @Test
    @Order(8)
    void anEmptyResultIsStillASealedBundle() throws Exception {
        JsonNode bundle = objectMapper.readTree(exportBody("?actorId=nobody-at-all"));

        assertThat(bundle.get("records")).isEmpty();
        assertThat(bundle.get("bundleHash").asText()).hasSize(64);
        assertThat(verifier.verify(bundle).intact()).isTrue();
    }

    @Test
    @Order(9)
    void aReaderTokenCanAlsoExport() throws Exception {
        mockMvc.perform(get("/audit/export?actorId=user-123")
                        .header("Authorization", "Bearer " + readerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(3));
    }

    @Test
    @Order(10)
    void exportWithoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/audit/export?actorId=user-123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(11)
    void actorAndResourceFiltersCombine() throws Exception {
        JsonNode bundle = objectMapper.readTree(
                exportBody("?actorId=user-123&resourceType=CLIENT_ACCOUNT&resourceId=acct-2"));

        assertThat(sequences(bundle)).containsExactly(5L);
        assertThat(verifier.verify(bundle).intact()).isTrue();
    }

    @Test
    @Order(12)
    void archivedRecordsStayInTheBundle() throws Exception {
        mockMvc.perform(post("/audit/admin/archive")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(5));

        JsonNode bundle = objectMapper.readTree(exportBody("?actorId=user-123"));

        // Retention hides records from queries, but an export is evidence and must stay complete.
        assertThat(sequences(bundle)).containsExactly(1L, 3L, 5L);
        assertThat(verifier.verify(bundle).intact()).isTrue();
        assertThat(query("").get("totalElements").asLong()).isZero();
    }

    private MvcResult exportRequest(String queryString) throws Exception {
        return mockMvc.perform(get("/audit/export" + queryString).header("X-API-Key", INGEST_KEY))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String exportBody(String queryString) throws Exception {
        return exportRequest(queryString).getResponse().getContentAsString();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<Long> sequences(JsonNode bundle) {
        List<Long> sequences = new ArrayList<>();
        bundle.get("records").forEach(record -> sequences.add(record.get("sequence").asLong()));
        return sequences;
    }
}
