package com.auditlog.api;

import com.auditlog.api.dto.AuditEventResponse;
import com.auditlog.api.dto.RedactEventRequest;
import com.auditlog.domain.RedactionCommand;
import com.auditlog.domain.RedactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Structured redaction. JWT with {@code audit.admin} only.
 *
 * <p>This is a POST that appends an overlay row, not an update of the event: the stored payload and
 * both hashes are untouched, which is what keeps the chain verifiable after a privacy request.
 */
@RestController
@Tag(name = "Redaction", description = "Mask sensitive payload fields without breaking the chain")
public class AuditRedactionController {

    private final RedactionService redactionService;

    public AuditRedactionController(RedactionService redactionService) {
        this.redactionService = redactionService;
    }

    @Operation(
            summary = "Redact payload fields on a record",
            description = "Appends redaction overlay rows and returns the record as readers now see "
                    + "it, with masked paths replaced by [REDACTED]. The stored payload is never "
                    + "rewritten, so GET /audit/verify stays intact and a direct SQL edit of the "
                    + "payload is still detected. Idempotent per field path. The operator id is taken "
                    + "from the JWT subject. Requires scope audit.admin.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Redaction applied"),
            @ApiResponse(responseCode = "400", description = "Unknown or malformed field path",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Credential lacks audit.admin, or is an API key",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "No such record",
                    content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/audit/events/{id}/redact")
    public AuditEventResponse redact(
            @PathVariable long id,
            @Valid @RequestBody RedactEventRequest request,
            @AuthenticationPrincipal Jwt operator
    ) {
        RedactionCommand command = new RedactionCommand(
                id, request.fieldPaths(), request.reason(), operator.getSubject());
        return AuditEventResponse.from(redactionService.redact(command));
    }
}
