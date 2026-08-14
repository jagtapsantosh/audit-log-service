package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComplianceCsvTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-14T11:30:00Z");
    private static final Instant RECORDED = Instant.parse("2026-08-14T11:37:00Z");

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final ComplianceCsv csv = new ComplianceCsv(mapper);

    @Test
    void headerIsTheDocumentedColumnSet() {
        assertThat(ComplianceCsv.HEADER.split(",")).containsExactly(
                "sequence", "eventType", "actorId", "resourceType", "resourceId",
                "occurredAt", "recordedAt", "status", "archivedAt",
                "contentHash", "previousHash", "payload", "redactedFields");
    }

    @Test
    void quotesPayloadJsonBecauseItContainsCommasAndQuotes() {
        AuditRecord record = new AuditRecord(
                1L, 1L, "ACCOUNT_VIEWED", "user-1", "CLIENT_ACCOUNT", "acct-1",
                canonicalJson.parse("{\"note\":\"hello, world\"}"),
                OCCURRED, RECORDED, "a".repeat(64), "0".repeat(64));
        AuditRecordView view = new AuditRecordView(
                record,
                new RedactionOverlay().mask(record.payload(), List.of("note")),
                List.of("note"));

        String rendered = csv.render(List.of(view));

        assertThat(rendered).startsWith(ComplianceCsv.HEADER + "\n");
        assertThat(rendered).contains("ACCOUNT_VIEWED");
        assertThat(rendered).contains("[REDACTED]");
        assertThat(rendered).contains("note");
        assertThat(rendered).doesNotContain("hello, world");
    }

    @Test
    void anEmptyEventListIsHeaderOnly() {
        assertThat(csv.render(List.of())).isEqualTo(ComplianceCsv.HEADER + "\n");
    }
}
