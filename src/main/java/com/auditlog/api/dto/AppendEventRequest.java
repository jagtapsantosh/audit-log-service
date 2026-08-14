package com.auditlog.api.dto;

import com.auditlog.domain.NewAuditEvent;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Write payload. Unknown properties are rejected so a caller cannot believe it has supplied
 * {@code sequence}, {@code recordedAt}, or a hash: those are server-assigned without exception.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(name = "AppendEventRequest", description = "An audit event to append to the chain")
public record AppendEventRequest(

        @Schema(example = "USER_LOGIN", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String eventType,

        @Schema(description = "Who or what caused the event", example = "user-123",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 255) String actorId,

        @Schema(example = "SESSION", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String resourceType,

        @Schema(example = "sess-abc", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 255) String resourceId,

        @Schema(description = """
                When the business event occurred, supplied by the caller (accepted as `timestamp` \
                too). May be arbitrarily old for offline or batched producers, but not more than \
                5 minutes ahead of the server clock. Truncated to microseconds. The server \
                additionally stamps `recordedAt` at ingest; both are hashed.""",
                example = "2026-08-14T11:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonAlias("timestamp")
        @NotNull Instant occurredAt,

        @Schema(description = "Structured, event-specific detail; must be a JSON object, max 64KB",
                example = "{\"ip\":\"10.0.0.1\"}")
        JsonNode payload
) {

    public NewAuditEvent toDomain() {
        return new NewAuditEvent(eventType, actorId, resourceType, resourceId, payload, occurredAt);
    }
}
