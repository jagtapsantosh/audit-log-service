package com.auditlog.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared plumbing for API integration tests. Each concrete subclass declares its own PostgreSQL
 * container so tests that depend on chain state (sequence numbers, tamper detection) are not
 * affected by another class's writes.
 *
 * <p>Credentials are the documented local evaluator credentials; authentication is exercised for
 * real rather than bypassed.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AuditApiIntegrationTest {

    protected static final String INGEST_KEY = "als_ingest_dev_key_do_not_use_in_prod";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected JsonNode appendEvent(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode appendEvent(
            String eventType,
            String actorId,
            String resourceType,
            String resourceId,
            String occurredAt,
            String payloadJson
    ) throws Exception {
        return appendEvent(eventBody(eventType, actorId, resourceType, resourceId, occurredAt, payloadJson));
    }

    protected static String eventBody(
            String eventType,
            String actorId,
            String resourceType,
            String resourceId,
            String occurredAt,
            String payloadJson
    ) {
        return """
                {"eventType":"%s","actorId":"%s","resourceType":"%s","resourceId":"%s",\
                "occurredAt":"%s","payload":%s}"""
                .formatted(eventType, actorId, resourceType, resourceId, occurredAt,
                        payloadJson == null ? "null" : payloadJson);
    }

    /** Verify is JWT-only, so integration tests must mint a token like any other reader would. */
    protected String readerToken() throws Exception {
        return token("audit.read");
    }

    /** Redaction and the retention sweep require audit.admin, and reject API keys outright. */
    protected String adminToken() throws Exception {
        return token("ops-admin", "ops-admin-secret-dev", "audit.read audit.admin");
    }

    /** Compliance APIs require audit.compliance; the regulator client is the documented caller. */
    protected String regulatorToken() throws Exception {
        return token("regulator", "regulator-secret-dev", "audit.read audit.compliance");
    }

    private String token(String scope) throws Exception {
        return token("ops-admin", "ops-admin-secret-dev", scope);
    }

    private String token(String clientId, String clientSecret, String scope) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_id":"%s","client_secret":"%s","scope":"%s"}
                                """.formatted(clientId, clientSecret, scope)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("access_token").asText();
    }

    protected JsonNode verifyChain() throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/verify")
                        .header("Authorization", "Bearer " + readerToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode query(String queryString) throws Exception {
        MvcResult result = mockMvc.perform(get("/audit/events" + queryString)
                        .header("X-API-Key", INGEST_KEY))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
