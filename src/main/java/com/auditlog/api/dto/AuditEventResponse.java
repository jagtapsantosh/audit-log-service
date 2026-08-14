package com.auditlog.api.dto;

import com.auditlog.domain.AuditRecord;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "AuditEvent", description = "A stored audit record and its chain links")
public record AuditEventResponse(
        Long id,
        @Schema(description = "Position in the global hash chain") long sequence,
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        JsonNode payload,
        @Schema(description = "Client clock: when the event occurred") Instant occurredAt,
        @Schema(description = "Server clock: when this service accepted the record") Instant recordedAt,
        @Schema(description = "SHA-256 of this record's canonical content") String contentHash,
        @Schema(description = "Predecessor's contentHash, or the genesis value for sequence 1")
        String previousHash
) {

    public static AuditEventResponse from(AuditRecord record) {
        return new AuditEventResponse(
                record.id(),
                record.sequence(),
                record.eventType(),
                record.actorId(),
                record.resourceType(),
                record.resourceId(),
                record.payload(),
                record.occurredAt(),
                record.recordedAt(),
                record.contentHash(),
                record.previousHash());
    }
}
