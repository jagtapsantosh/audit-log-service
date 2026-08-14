package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Write contract, query filters, and paging against a real PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class AuditEventApiIT extends AuditApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static boolean seeded;

    /** Seeds once per class; the log is append-only, so tests read a shared fixture. */
    private void seedOnce() throws Exception {
        if (seeded) {
            return;
        }
        appendEvent("USER_LOGIN", "user-1", "SESSION", "sess-1", "2026-08-14T09:00:00Z",
                "{\"ip\":\"10.0.0.1\"}");
        appendEvent("USER_LOGIN", "user-2", "SESSION", "sess-2", "2026-08-14T10:00:00Z", null);
        appendEvent("ACCOUNT_VIEWED", "user-1", "CLIENT_ACCOUNT", "acct-9", "2026-08-14T11:00:00Z",
                "{\"channel\":\"web\"}");
        appendEvent("ACCOUNT_UPDATED", "user-1", "CLIENT_ACCOUNT", "acct-9", "2026-08-14T12:00:00Z",
                "{\"field\":\"address\"}");
        appendEvent("PERMISSION_GRANTED", "admin-1", "CLIENT_ACCOUNT", "acct-10",
                "2026-08-14T13:00:00Z", null);
        seeded = true;
    }

    @Test
    void appendReturnsChainMetadataAndPersistsBothClocks() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        JsonNode created = appendEvent("USER_LOGIN", "user-clock", "SESSION", "sess-clock",
                "2026-08-14T11:30:00Z", "{\"ip\":\"10.0.0.9\"}");

        assertThat(created.get("id").asLong()).isPositive();
        assertThat(created.get("sequence").asLong()).isPositive();
        assertThat(created.get("occurredAt").asText()).isEqualTo("2026-08-14T11:30:00Z");
        assertThat(Instant.parse(created.get("recordedAt").asText())).isAfterOrEqualTo(before);
        assertThat(created.get("contentHash").asText()).hasSize(64);
    }

    @Test
    @DisplayName("the PDF's `timestamp` field name is accepted as an alias for occurredAt")
    void acceptsTimestampAlias() throws Exception {
        JsonNode created = appendEvent("""
                {"eventType":"USER_LOGIN","actorId":"user-alias","resourceType":"SESSION",\
                "resourceId":"sess-alias","timestamp":"2026-08-14T11:30:00Z"}""");

        assertThat(created.get("occurredAt").asText()).isEqualTo("2026-08-14T11:30:00Z");
    }

    @Test
    @DisplayName("numbers survive the jsonb round trip without breaking the chain")
    void numericPayloadsRemainVerifiable() throws Exception {
        // jsonb re-renders numbers (1e2 becomes 100); canonical hashing must be immune to that.
        appendEvent("RECORD_UPDATED", "user-num", "CLIENT_ACCOUNT", "acct-num",
                "2026-08-14T11:30:00Z", "{\"amount\":1.0,\"scaled\":1.50,\"exp\":1e2,\"n\":7}");

        assertThat(verifyChain().get("intact").asBoolean()).isTrue();
    }

    @Test
    void rejectsClientSuppliedChainFields() throws Exception {
        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType":"USER_LOGIN","actorId":"a","resourceType":"SESSION",\
                                "resourceId":"s","occurredAt":"2026-08-14T11:30:00Z","sequence":99}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"USER_LOGIN\",\"actorId\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void rejectsNonObjectPayload() throws Exception {
        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("USER_LOGIN", "a", "SESSION", "s",
                                "2026-08-14T11:30:00Z", "[1,2,3]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYLOAD_NOT_OBJECT"));
    }

    @Test
    void rejectsEventsClaimingToHappenInTheFuture() throws Exception {
        String farFuture = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        mockMvc.perform(post("/audit/events")
                        .header("X-API-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("USER_LOGIN", "a", "SESSION", "s", farFuture, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OCCURRED_AT_IN_FUTURE"));
    }

    @Test
    @DisplayName("append-only: update and delete verbs are not mapped")
    void updateAndDeleteAreNotAvailable() throws Exception {
        mockMvc.perform(put("/audit/events").header("X-API-Key", INGEST_KEY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(delete("/audit/events").header("X-API-Key", INGEST_KEY))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void filtersByActor() throws Exception {
        seedOnce();

        JsonNode page = query("?actorId=user-2");

        assertThat(actorIds(page)).containsOnly("user-2");
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void filtersByResourceTypeAndId() throws Exception {
        seedOnce();

        JsonNode page = query("?resourceType=CLIENT_ACCOUNT&resourceId=acct-9");

        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(StreamSupport.stream(page.get("content").spliterator(), false)
                .map(node -> node.get("resourceId").asText()))
                .containsOnly("acct-9");
    }

    @Test
    void filtersByEventType() throws Exception {
        seedOnce();

        JsonNode page = query("?eventType=USER_LOGIN&actorId=user-1");

        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("time range filters the client event clock, inclusively")
    void filtersByOccurredAtRange() throws Exception {
        seedOnce();

        JsonNode page = query("?actorId=user-1&from=2026-08-14T11:00:00Z&to=2026-08-14T12:00:00Z");

        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void combinesFilters() throws Exception {
        seedOnce();

        JsonNode page = query("?actorId=user-1&resourceType=CLIENT_ACCOUNT&resourceId=acct-9"
                + "&eventType=ACCOUNT_VIEWED&from=2026-08-14T00:00:00Z&to=2026-08-14T23:59:59Z");

        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("eventType").asText()).isEqualTo("ACCOUNT_VIEWED");
    }

    @Test
    void filtersByServerIngestClock() throws Exception {
        seedOnce();

        JsonNode none = query("?recordedTo=2020-01-01T00:00:00Z");
        JsonNode all = query("?recordedFrom=2020-01-01T00:00:00Z");

        assertThat(none.get("totalElements").asLong()).isZero();
        assertThat(all.get("totalElements").asLong()).isPositive();
    }

    @Test
    void rejectsInvertedTimeRange() throws Exception {
        mockMvc.perform(get("/audit/events?from=2026-08-14T12:00:00Z&to=2026-08-14T11:00:00Z")
                        .header("X-API-Key", INGEST_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }

    @Test
    @DisplayName("paging is ordered by chain sequence and capped at the documented maximum")
    void pagesInChainOrder() throws Exception {
        seedOnce();

        JsonNode firstPage = query("?size=2&page=0");
        JsonNode secondPage = query("?size=2&page=1");

        assertThat(sequences(firstPage)).isSorted().hasSize(2);
        assertThat(sequences(secondPage)).isSorted().hasSize(2);
        assertThat(sequences(firstPage).get(1)).isLessThan(sequences(secondPage).get(0));
        assertThat(query("?size=5000").get("size").asInt()).isEqualTo(200);
    }

    private List<String> actorIds(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(node -> node.get("actorId").asText())
                .toList();
    }

    private List<Long> sequences(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(node -> node.get("sequence").asLong())
                .toList();
    }
}
