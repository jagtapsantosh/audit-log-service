package com.auditlog.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "audit.security.max-request-bytes=256",
        "audit.security.rate-limit.write-per-minute=2"
})
class RequestLimitIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void anOversizedDeclaredBodyIsRejected() throws Exception {
        String oversized = eventBody("USER_LOGIN", "user-1", "SESSION", "sess-1",
                "2026-08-14T11:30:00Z", "{\"blob\":\"" + "x".repeat(300) + "\"}");
        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void aThirdWriteInTheWindowIsRateLimited() throws Exception {
        appendEvent("USER_LOGIN", "user-2", "SESSION", "sess-2",
                "2026-08-14T11:31:00Z", null);
        appendEvent("USER_LOGIN", "user-3", "SESSION", "sess-3",
                "2026-08-14T11:32:00Z", null);
        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("USER_LOGIN", "user-4", "SESSION", "sess-4",
                                "2026-08-14T11:33:00Z", null)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
