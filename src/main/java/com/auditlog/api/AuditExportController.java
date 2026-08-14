package com.auditlog.api;

import com.auditlog.domain.ExportBundle;
import com.auditlog.domain.ExportBundleService;
import com.auditlog.domain.ExportFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk export as a downloadable, self-verifying bundle.
 *
 * <p>The domain {@link ExportBundle} is serialized directly rather than re-mapped into an API DTO on
 * purpose: {@code bundleHash} is computed over these exact bytes, so introducing a parallel wire type
 * would risk the recipient hashing a different shape than the server did.
 */
@RestController
@Tag(name = "Export", description = "Verifiable bundle for one actor or resource")
public class AuditExportController {

    private final ExportBundleService exportBundleService;

    public AuditExportController(ExportBundleService exportBundleService) {
        this.exportBundleService = exportBundleService;
    }

    @Operation(
            summary = "Export all records for an actor or resource",
            description = "Returns a self-contained JSON bundle. bundleHash proves the file has not "
                    + "been altered since export; a recipient can also re-hash any record with no "
                    + "redactions. Sequence gaps are expected, because a filtered export is a sparse "
                    + "slice of the global chain. Archived records are included. Requires scope "
                    + "audit.read.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bundle produced"),
            @ApiResponse(responseCode = "400", description = "No subject given, or too many records",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Credential lacks audit.read",
                    content = @io.swagger.v3.oas.annotations.media.Content)
    })
    @SecurityRequirement(name = "apiKey")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(path = "/audit/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExportBundle> export(
            @Parameter(description = "Export every record caused by this actor")
            @RequestParam(required = false) String actorId,
            @Parameter(description = "Narrows a resourceId export; optional")
            @RequestParam(required = false) String resourceType,
            @Parameter(description = "Export every record about this resource")
            @RequestParam(required = false) String resourceId
    ) {
        ExportBundle bundle = exportBundleService.export(
                new ExportFilter(actorId, resourceType, resourceId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filenameFor(actorId, resourceId))
                        .build()
                        .toString())
                .body(bundle);
    }

    private static String filenameFor(String actorId, String resourceId) {
        String subject = actorId != null && !actorId.isBlank() ? actorId : resourceId;
        return "audit-export-" + subject.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }
}
