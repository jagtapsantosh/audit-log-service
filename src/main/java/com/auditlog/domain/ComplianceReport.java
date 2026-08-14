package com.auditlog.domain;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time access report. {@code reportId} is minted per request (reports are not stored).
 * {@code chainHeadHash} pins the live chain head at {@code generatedAt} so a reviewer can cross-check
 * {@code GET /audit/verify} without this object claiming to re-hash the universe.
 */
public record ComplianceReport(
        String reportId,
        Instant generatedAt,
        String chainHeadHash,
        ComplianceFilter filter,
        AccessSummary summary,
        List<AuditRecordView> events,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String verificationHint
) {}
