package com.auditlog.api.dto;

import com.auditlog.domain.RetentionService.ArchiveResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "ArchiveResponse", description = "Outcome of a retention sweep")
public record ArchiveResponse(
        @Schema(description = "How many records changed from ACTIVE to ARCHIVED") int archived,
        @Schema(description = "Records ingested before this instant were archived") Instant cutoff,
        @Schema(description = "Configured retention window in days") int retentionDays
) {

    public static ArchiveResponse from(ArchiveResult result) {
        return new ArchiveResponse(result.archived(), result.cutoff(), result.retentionDays());
    }
}
