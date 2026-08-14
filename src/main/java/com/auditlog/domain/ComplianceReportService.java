package com.auditlog.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Scenario C access report.
 *
 * <p>The service never lets the caller choose {@code resourceType} or {@code eventType}: those are
 * frozen in {@link AccessScope}. Archived rows are included (exams need history). Events are the
 * redaction view, so a regulator sees the same masked payload as any other reader.
 *
 * <p>The report is a snapshot, not a stored filing: {@code reportId} is minted per request, and
 * {@code chainHeadHash} is the live chain head at {@code generatedAt}. New writes after that will
 * move the head; that is expected.
 */
@Service
public class ComplianceReportService {

    public static final int DEFAULT_PAGE_SIZE = AuditQueryService.DEFAULT_PAGE_SIZE;
    public static final int MAX_PAGE_SIZE = AuditQueryService.MAX_PAGE_SIZE;
    public static final int MAX_EXPORT_RECORDS = ExportBundleService.MAX_RECORDS;

    private static final Logger log = LoggerFactory.getLogger(ComplianceReportService.class);

    private final AuditRecordStore recordStore;
    private final AuditRedactionStore redactionStore;
    private final RedactionOverlay overlay;
    private final Clock clock;

    public ComplianceReportService(
            AuditRecordStore recordStore,
            AuditRedactionStore redactionStore,
            RedactionOverlay overlay,
            Clock clock
    ) {
        this.recordStore = recordStore;
        this.redactionStore = redactionStore;
        this.overlay = overlay;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ComplianceReport report(ComplianceAccessFilter filter, Integer page, Integer size) {
        validate(filter);
        int requestedPage = page == null ? 0 : Math.max(page, 0);
        int requestedSize = size == null ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        PageResult<AuditRecord> stored = recordStore.searchAccess(filter, requestedPage, requestedSize);
        return assemble(filter, applyRedactions(stored.content()), stored);
    }

    /**
     * Same filter as {@link #report}, but every matching row (capped) rather than a page. Used by
     * the CSV/JSON download.
     */
    @Transactional(readOnly = true)
    public ComplianceReport export(ComplianceAccessFilter filter) {
        validate(filter);
        List<AuditRecord> stored = recordStore.findAccess(filter, MAX_EXPORT_RECORDS + 1);
        if (stored.size() > MAX_EXPORT_RECORDS) {
            throw new InvalidComplianceRequestException("COMPLIANCE_EXPORT_TOO_LARGE",
                    "report matches more than " + MAX_EXPORT_RECORDS + " records; narrow the filter");
        }
        PageResult<AuditRecord> asPage = PageResult.of(stored, 0, Math.max(stored.size(), 1), stored.size());
        return assemble(filter, applyRedactions(stored), asPage);
    }

    private ComplianceReport assemble(
            ComplianceAccessFilter filter,
            List<AuditRecordView> events,
            PageResult<AuditRecord> page
    ) {
        Instant generatedAt = CanonicalJson.canonicalInstant(clock.instant());
        String chainHeadHash = recordStore.findChainHead()
                .map(AuditRecord::contentHash)
                .orElse(HashChainService.GENESIS_HASH);
        AccessSummary summary = recordStore.summarizeAccess(filter);
        String reportId = UUID.randomUUID().toString();
        log.info("Compliance access report id={} matches={} head={} actor={} resource={}",
                reportId, summary.totalAccessEvents(), chainHeadHash, filter.actorId(), filter.resourceId());
        return new ComplianceReport(
                reportId,
                generatedAt,
                chainHeadHash,
                ComplianceFilter.of(filter),
                summary,
                events,
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                AccessScope.VERIFICATION_HINT);
    }

    private List<AuditRecordView> applyRedactions(List<AuditRecord> records) {
        List<Long> redactedRecordIds = records.stream()
                .filter(AuditRecord::hasRedactions)
                .map(AuditRecord::id)
                .toList();
        if (redactedRecordIds.isEmpty()) {
            return records.stream().map(AuditRecordView::of).toList();
        }
        Map<Long, List<Redaction>> redactions = redactionStore.findByRecordIds(redactedRecordIds);
        return records.stream()
                .map(record -> overlay.apply(record, redactions.getOrDefault(record.id(), List.of())))
                .toList();
    }

    private static void validate(ComplianceAccessFilter filter) {
        if (filter.occurredFrom() != null
                && filter.occurredTo() != null
                && filter.occurredFrom().isAfter(filter.occurredTo())) {
            throw new InvalidComplianceRequestException("INVALID_QUERY",
                    "'from' must not be after 'to'");
        }
    }
}
