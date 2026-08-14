package com.auditlog.api;

import com.auditlog.api.dto.AccessReportResponse;
import com.auditlog.domain.ComplianceAccessFilter;
import com.auditlog.domain.ComplianceCsv;
import com.auditlog.domain.ComplianceReport;
import com.auditlog.domain.ComplianceReportService;
import com.auditlog.domain.InvalidComplianceRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scenario C: read-only access report for regulators. JWT {@code audit.compliance} only.
 *
 * <p>The resource type and event-type set are frozen in the service. Callers can only narrow by
 * account, actor, and occurredAt range.
 */
@RestController
@Tag(name = "Compliance", description = "Access to client account data")
public class ComplianceReportController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv");

    private final ComplianceReportService complianceReportService;
    private final ComplianceCsv complianceCsv;
    private final ObjectMapper objectMapper;

    public ComplianceReportController(
            ComplianceReportService complianceReportService,
            ComplianceCsv complianceCsv,
            ObjectMapper objectMapper
    ) {
        this.complianceReportService = complianceReportService;
        this.complianceCsv = complianceCsv;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Access report for client account data",
            description = "Lists CLIENT_ACCOUNT events of types ACCOUNT_VIEWED, ACCOUNT_UPDATED, "
                    + "STATEMENT_DOWNLOADED, PERMISSION_GRANTED. Archived rows are included. "
                    + "chainHeadHash is the live chain head at generatedAt. Requires JWT scope "
                    + "audit.compliance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Snapshot produced"),
            @ApiResponse(responseCode = "400", description = "Inverted time range",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials",
                    content = @Content),
            @ApiResponse(responseCode = "403", description = "Credential lacks audit.compliance, or is an API key",
                    content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/audit/compliance/access-report")
    public AccessReportResponse report(
            @Parameter(description = "Account id (resourceId)")
            @RequestParam(required = false) String resourceId,
            @Parameter(description = "Who accessed the account")
            @RequestParam(required = false) String actorId,
            @Parameter(description = "Inclusive lower bound on occurredAt")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Inclusive upper bound on occurredAt")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; default 50, maximum 200")
            @RequestParam(required = false) Integer size
    ) {
        return AccessReportResponse.from(complianceReportService.report(
                new ComplianceAccessFilter(actorId, resourceId, from, to), page, size));
    }

    @Operation(
            summary = "Download the access report",
            description = """
                    Same filter as GET /audit/compliance/access-report, as a file. format=json \
                    (default) is the full snapshot; format=csv is one row per event with columns \
                    sequence, eventType, actorId, resourceType, resourceId, occurredAt, recordedAt, \
                    status, archivedAt, contentHash, previousHash, payload, redactedFields.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "File produced"),
            @ApiResponse(responseCode = "400", description = "Unknown format, inverted range, or too many rows",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials",
                    content = @Content),
            @ApiResponse(responseCode = "403", description = "Credential lacks audit.compliance, or is an API key",
                    content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/audit/compliance/access-report/export")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @Parameter(description = "csv or json; default json")
            @RequestParam(required = false, defaultValue = "json") String format
    ) {
        ComplianceReport report = complianceReportService.export(
                new ComplianceAccessFilter(actorId, resourceId, from, to));
        ExportFormat exportFormat = ExportFormat.from(format);
        AccessReportResponse body = AccessReportResponse.from(report);
        return switch (exportFormat) {
            case CSV -> download(
                    "access-report-" + report.reportId() + ".csv",
                    CSV,
                    complianceCsv.render(report.events()));
            case JSON -> download(
                    "access-report-" + report.reportId() + ".json",
                    MediaType.APPLICATION_JSON,
                    json(body));
        };
    }

    private String json(AccessReportResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize compliance export", e);
        }
    }

    private static ResponseEntity<String> download(String filename, MediaType mediaType, String body) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename)
                        .build()
                        .toString())
                .body(body);
    }

    private enum ExportFormat {
        JSON, CSV;

        static ExportFormat from(String format) {
            if (format == null || format.isBlank() || format.equalsIgnoreCase("json")) {
                return JSON;
            }
            if (format.equalsIgnoreCase("csv")) {
                return CSV;
            }
            throw new InvalidComplianceRequestException("INVALID_FORMAT",
                    "format must be csv or json");
        }
    }
}
