package com.auditlog.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the log. Filters are combinable and results are always ordered by chain sequence, so
 * paging is stable while new records are being appended.
 */
@Service
public class AuditQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    private final AuditRecordStore store;

    public AuditQueryService(AuditRecordStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public PageResult<AuditRecord> search(AuditQueryFilter filter, Integer page, Integer size) {
        validate(filter);
        int requestedPage = page == null ? 0 : Math.max(page, 0);
        int requestedSize = size == null ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        return store.search(filter, requestedPage, requestedSize);
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
