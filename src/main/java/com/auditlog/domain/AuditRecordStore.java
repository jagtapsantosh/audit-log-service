package com.auditlog.domain;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the audit chain. Deliberately offers no update or delete operation: the only
 * write is an append. Implemented in the persistence layer.
 */
public interface AuditRecordStore {

    /**
     * Serializes appends within the current transaction so two writers cannot claim the same
     * sequence number or predecessor hash.
     */
    void lockChain();

    Optional<AuditRecord> findChainHead();

    AuditRecord append(AuditRecord record);

    long count();

    PageResult<AuditRecord> search(AuditQueryFilter filter, int page, int size);

    /** Ordered chain slice used by verification, keyset-paged to bound memory on large chains. */
    List<AuditRecord> findSliceAfter(long exclusiveSequence, int limit);
}
