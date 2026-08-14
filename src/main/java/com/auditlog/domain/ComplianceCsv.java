package com.auditlog.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CSV rendering of a compliance report. Kept in the domain so the column set is documented next to
 * the report itself rather than invented in a controller.
 *
 * <p>Columns: {@code sequence, eventType, actorId, resourceType, resourceId, occurredAt, recordedAt,
 * status, archivedAt, contentHash, previousHash, payload, redactedFields}.
 */
@Component
public final class ComplianceCsv {

    public static final String HEADER = String.join(",",
            "sequence", "eventType", "actorId", "resourceType", "resourceId",
            "occurredAt", "recordedAt", "status", "archivedAt",
            "contentHash", "previousHash", "payload", "redactedFields");

    private final ObjectMapper objectMapper;

    public ComplianceCsv(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String render(List<AuditRecordView> events) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (AuditRecordView view : events) {
            AuditRecord record = view.record();
            csv.append(String.join(",",
                    Long.toString(record.sequence()),
                    quote(record.eventType()),
                    quote(record.actorId()),
                    quote(record.resourceType()),
                    quote(record.resourceId()),
                    quote(record.occurredAt().toString()),
                    quote(record.recordedAt().toString()),
                    quote(record.status().name()),
                    quote(record.archivedAt() == null ? "" : record.archivedAt().toString()),
                    quote(record.contentHash()),
                    quote(record.previousHash()),
                    quote(json(view.visiblePayload())),
                    quote(String.join(";", view.redactedFields()))));
            csv.append('\n');
        }
        return csv.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize payload for CSV", e);
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('"') >= 0 || value.indexOf(',') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
