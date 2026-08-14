package com.auditlog.api;

import com.auditlog.api.dto.AppendEventRequest;
import com.auditlog.api.dto.AuditEventResponse;
import com.auditlog.domain.AuditWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Append-only write API. There is deliberately no PUT, PATCH, or DELETE mapping for audit events
 * anywhere in this service.
 */
@RestController
@Tag(name = "Audit events", description = "Append and read the tamper-evident log")
public class AuditWriteController {

    private final AuditWriteService writeService;

    public AuditWriteController(AuditWriteService writeService) {
        this.writeService = writeService;
    }

    @Operation(
            summary = "Append an audit event",
            description = "Assigns the next chain sequence, stamps the server clock, and links the "
                    + "record to its predecessor's hash. Requires scope audit.write.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Appended"),
            @ApiResponse(responseCode = "400", description = "Validation, payload, or clock-skew failure",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Credential lacks audit.write",
                    content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @SecurityRequirement(name = "apiKey")
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(path = "/audit/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AuditEventResponse append(@Valid @RequestBody AppendEventRequest request) {
        return AuditEventResponse.from(writeService.append(request.toDomain()));
    }
}
