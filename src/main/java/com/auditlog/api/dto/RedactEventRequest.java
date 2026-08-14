package com.auditlog.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Redaction request.
 *
 * <p>There is deliberately no {@code redactedBy} field: the operator identity comes from the JWT
 * subject, so the privacy trail records who actually called rather than who the caller claims to be.
 * Sending it is rejected as an unknown field.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(name = "RedactEventRequest", description = "Payload field paths to mask on a stored record")
public record RedactEventRequest(

        @Schema(description = "Dotted paths into the payload, e.g. accountNumber or customer.ssn",
                example = "[\"accountNumber\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<@Size(max = 255) String> fieldPaths,

        @Schema(description = "Why the data is being redacted, for the privacy trail", example = "GDPR")
        @Size(max = 500) String reason
) {}
