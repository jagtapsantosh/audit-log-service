package com.auditlog.api;

import com.auditlog.api.dto.ArchiveResponse;
import com.auditlog.domain.RetentionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retention administration. JWT with {@code audit.admin} only: a leaked ingest API key must not be
 * able to change the retention state of the log.
 */
@RestController
@Tag(name = "Retention", description = "Soft archive past the retention window")
public class AuditAdminController {

    private final RetentionService retentionService;

    public AuditAdminController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Operation(
            summary = "Run the retention sweep now",
            description = "Marks records ingested before the retention cutoff as ARCHIVED. Nothing is "
                    + "deleted and no hashed field changes, so GET /audit/verify still walks every "
                    + "record and stays intact. Idempotent. Requires scope audit.admin.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sweep completed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Credential lacks audit.admin, or is an API key",
                    content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/audit/admin/archive")
    public ArchiveResponse archive() {
        return ArchiveResponse.from(retentionService.sweep());
    }
}
