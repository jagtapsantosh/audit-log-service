package com.auditlog.api;

import com.auditlog.api.dto.AuditEventResponse;
import com.auditlog.api.dto.PagedResponse;
import com.auditlog.domain.AuditQueryFilter;
import com.auditlog.domain.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read API. Every filter is optional and any combination is supported. */
@RestController
@Tag(name = "Audit events")
public class AuditQueryController {

    private final AuditQueryService queryService;

    public AuditQueryController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(
            summary = "Query audit events",
            description = "Filters combine with AND. Results are always ordered by chain sequence, so "
                    + "paging stays stable while new events are appended. Requires scope audit.read.")
    @SecurityRequirement(name = "apiKey")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/audit/events")
    public PagedResponse<AuditEventResponse> query(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Inclusive lower bound on occurredAt (client clock), ISO-8601")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Inclusive upper bound on occurredAt (client clock), ISO-8601")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Inclusive lower bound on recordedAt (server ingest clock)")
            @RequestParam(required = false) Instant recordedFrom,
            @Parameter(description = "Inclusive upper bound on recordedAt (server ingest clock)")
            @RequestParam(required = false) Instant recordedTo,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; default 50, maximum 200")
            @RequestParam(required = false) Integer size
    ) {
        AuditQueryFilter filter = new AuditQueryFilter(
                actorId, resourceType, resourceId, eventType, from, to, recordedFrom, recordedTo);
        return PagedResponse.from(queryService.search(filter, page, size), AuditEventResponse::from);
    }
}
