package com.auditlog.api.dto;

import com.auditlog.domain.AuditRecord;
import com.auditlog.domain.AuditRecordView;
import com.auditlog.domain.RecordStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(name = "AuditEvent", description = "A stored audit record and its chain links")
public record AuditEventResponse(
        Long id,
        @Schema(description = "Position in the global hash chain") long sequence,
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        @Schema(description = "Event detail; redacted paths are replaced with [REDACTED]")
        JsonNode payload,
        @Schema(description = "Client clock: when the event occurred") Instant occurredAt,
        @Schema(description = "Server clock: when this service accepted the record") Instant recordedAt,
        @Schema(description = "SHA-256 of this record's canonical content, over the original payload")
        String contentHash,
        @Schema(description = "Predecessor's contentHash, or the genesis value for sequence 1")
        String previousHash,
        @Schema(description = "ACTIVE, or ARCHIVED once past the retention window") RecordStatus status,
        @Schema(description = "When the retention sweep archived this record")
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant archivedAt,
        @Schema(description = "Payload paths masked by a redaction; empty when nothing is redacted")
        List<String> redactedFields
) {

    /** For the write path, where nothing can be archived or redacted yet. */
    public static AuditEventResponse from(AuditRecord record) {
        return from(AuditRecordView.of(record));
    }

    public static AuditEventResponse from(AuditRecordView view) {
        AuditRecord record = view.record();
        return new AuditEventResponse(
                record.id(),
                record.sequence(),
                record.eventType(),
                record.actorId(),
                record.resourceType(),
                record.resourceId(),
                view.visiblePayload(),
                record.occurredAt(),
                record.recordedAt(),
                record.contentHash(),
                record.previousHash(),
                record.status(),
                record.archivedAt(),
                view.redactedFields());
    }
}
