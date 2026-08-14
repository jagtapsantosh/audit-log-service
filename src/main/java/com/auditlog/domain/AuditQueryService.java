package com.auditlog.domain;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the log. Filters are combinable and results are always ordered by chain sequence, so
 * paging is stable while new records are being appended.
 *
 * <p>Results come back as {@link AuditRecordView}s: this is the layer that applies the redaction
 * overlay, so no read path can accidentally return a value that was redacted. Verification uses the
 * store directly and is unaffected.
 */
@Service
public class AuditQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    private final AuditRecordStore store;
    private final AuditRedactionStore redactionStore;
    private final RedactionOverlay overlay;

    public AuditQueryService(
            AuditRecordStore store,
            AuditRedactionStore redactionStore,
            RedactionOverlay overlay
    ) {
        this.store = store;
        this.redactionStore = redactionStore;
        this.overlay = overlay;
    }

    @Transactional(readOnly = true)
    public PageResult<AuditRecordView> search(AuditQueryFilter filter, Integer page, Integer size) {
        validate(filter);
        int requestedPage = page == null ? 0 : Math.max(page, 0);
        int requestedSize = size == null ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        PageResult<AuditRecord> stored = store.search(filter, requestedPage, requestedSize);
        return new PageResult<>(
                applyRedactions(stored.content()),
                stored.page(),
                stored.size(),
                stored.totalElements(),
                stored.totalPages());
    }

    /** One batched lookup per page rather than one per row. */
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

    private void validate(AuditQueryFilter filter) {
        if (filter.occurredFrom() != null
                && filter.occurredTo() != null
                && filter.occurredFrom().isAfter(filter.occurredTo())) {
            throw new InvalidAuditQueryException("'from' must not be after 'to'");
        }
        if (filter.recordedFrom() != null
                && filter.recordedTo() != null
                && filter.recordedFrom().isAfter(filter.recordedTo())) {
            throw new InvalidAuditQueryException("'recordedFrom' must not be after 'recordedTo'");
        }
    }

    public static class InvalidAuditQueryException extends RuntimeException {
        public InvalidAuditQueryException(String message) {
            super(message);
        }
    }
}
