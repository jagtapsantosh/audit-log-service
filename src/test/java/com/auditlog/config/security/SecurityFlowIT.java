package com.auditlog.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SecurityFlowIT {

    private static final String INGEST_KEY = "als_ingest_dev_key_do_not_use_in_prod";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void writeWithoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(post("/audit/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void verifyWithoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/audit/verify"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingestApiKeyCannotCallVerify() throws Exception {
        mockMvc.perform(get("/audit/verify").header("X-API-Key", INGEST_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void ingestApiKeyIsAcceptedForWritePath() throws Exception {
        // Authorized but empty body: the request reaches validation, which is what proves the key
        // satisfied audit.write rather than being rejected by the filter chain.
        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidApiKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/audit/events").header("X-API-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenEndpointIssuesJwtForOpsAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_id":"ops-admin","client_secret":"ops-admin-secret-dev","scope":"audit.read audit.admin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = body.get("access_token").asText();

        mockMvc.perform(get("/audit/verify").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
    }

    @Test
    void jwtWithoutWriteScopeCannotAppend() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_id":"ops-admin","client_secret":"ops-admin-secret-dev","scope":"audit.read"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("access_token")
                .asText();

        mockMvc.perform(post("/audit/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"USER_LOGIN","actorId":"user-1","resourceType":"SESSION",\
                                "resourceId":"sess-1","occurredAt":"2026-08-14T11:30:00Z"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void regulatorTokenCannotCallAdminArchive() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_id":"regulator","client_secret":"regulator-secret-dev"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("access_token")
                .asText();

        mockMvc.perform(post("/audit/admin/archive").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidClientSecretIsUnauthorized() throws Exception {
        mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_id":"ops-admin","client_secret":"nope"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
