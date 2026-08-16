package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IdempotencyIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void theSameKeyAndBodyReturnTheOriginalRecord() throws Exception {
        String body = eventBody("USER_LOGIN", "user-1", "SESSION", "sess-1",
                "2026-08-14T11:30:00Z", "{\"ip\":\"10.0.0.1\"}");

        JsonNode first = appendWithKey("retry-1", body, 201);
        JsonNode second = appendWithKey("retry-1", body, 200);

        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(second.get("sequence").asLong()).isEqualTo(first.get("sequence").asLong());
        assertThat(second.get("contentHash").asText()).isEqualTo(first.get("contentHash").asText());
        assertThat(query("").get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() throws Exception {
        String first = eventBody("USER_LOGIN", "user-2", "SESSION", "sess-2",
                "2026-08-14T11:31:00Z", "{\"ip\":\"10.0.0.2\"}");
        String different = eventBody("USER_LOGIN", "user-2", "SESSION", "sess-2",
                "2026-08-14T11:31:00Z", "{\"ip\":\"10.0.0.9\"}");

        appendWithKey("retry-2", first, 201);
        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .header("Idempotency-Key", "retry-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void writesWithoutAKeyAlwaysAppend() throws Exception {
        String body = eventBody("USER_LOGIN", "user-3", "SESSION", "sess-3",
                "2026-08-14T11:32:00Z", null);
        appendEvent(body);
        appendEvent(body);
        assertThat(query("?actorId=user-3").get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void aMalformedKeyIsRejected() throws Exception {
        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .header("Idempotency-Key", "has spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("USER_LOGIN", "user-4", "SESSION", "sess-4",
                                "2026-08-14T11:33:00Z", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    private JsonNode appendWithKey(String key, String body, int status) throws Exception {
        MvcResult result = mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(status))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
