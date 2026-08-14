package com.auditlog.api.dto;

import com.auditlog.domain.AccessSummary;
import com.auditlog.domain.ComplianceFilter;
import com.auditlog.domain.ComplianceReport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(name = "AccessReport", description = """
        Point-in-time report of access to CLIENT_ACCOUNT data. chainHeadHash is the live chain \
        head at generatedAt; GET /audit/verify must still be intact for the snapshot to be \
        trustworthy. Events are the redaction view.""")
public record AccessReportResponse(
        String reportId,
        Instant generatedAt,
        @Schema(description = "contentHash of the current chain head (max sequence) at generatedAt")
        String chainHeadHash,
        ComplianceFilter filter,
        AccessSummary summary,
        List<AuditEventResponse> events,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String verificationHint
) {

    public static AccessReportResponse from(ComplianceReport report) {
        return new AccessReportResponse(
                report.reportId(),
                report.generatedAt(),
                report.chainHeadHash(),
                report.filter(),
                report.summary(),
                report.events().stream().map(AuditEventResponse::from).toList(),
                report.page(),
                report.size(),
                report.totalElements(),
                report.totalPages(),
                report.verificationHint());
    }
}
