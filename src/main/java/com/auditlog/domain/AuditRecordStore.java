package com.auditlog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the audit chain.
 *
 * <p>There is still no operation here that can rewrite hashed content or delete a row. The two
 * mutating methods added in Scenario B ({@link #archiveRecordedBefore} and
 * {@link #markHasRedactions}) touch only retention and privacy metadata, which sits outside the hash
 * pre-image. Everything a {@code contentHash} covers remains write-once.
 */
public interface AuditRecordStore {

    /**
     * Serializes appends within the current transaction so two writers cannot claim the same
     * sequence number or predecessor hash.
     */
    void lockChain();

    Optional<AuditRecord> findChainHead();

    AuditRecord append(AuditRecord record);

    /**
     * Publishes the newest {@code (sequence, contentHash)} this service just wrote. Verify compares
     * this pointer to the current table so a SQL delete of the tail is visible.
     */
    void publishHead(ChainHead head, Instant updatedAt);

    Optional<ChainHead> findPublishedHead();

    long count();

    Optional<AuditRecord> findById(long id);

    PageResult<AuditRecord> search(AuditQueryFilter filter, int page, int size);

    /** Ordered chain slice used by verification, keyset-paged to bound memory on large chains. */
    List<AuditRecord> findSliceAfter(long exclusiveSequence, int limit);

    /**
     * Soft-archives every ACTIVE record recorded strictly before {@code cutoff}. Returns how many
     * rows changed state. Rows are never deleted, so the chain keeps every link.
     */
    int archiveRecordedBefore(Instant cutoff, Instant archivedAt);

    /** Sets the cached {@code has_redactions} flag after an overlay row is written. */
    void markHasRedactions(long recordId);

    /** Chain-ordered slice for an export subject; {@code limit} bounds the bundle size. */
    List<AuditRecord> findForExport(ExportFilter filter, int limit);

    /**
     * Access events for Scenario C: {@code CLIENT_ACCOUNT} plus the frozen access event-type set.
     * Archived rows are included. Optional actor / account / occurredAt bounds come from
     * {@code filter}.
     */
    PageResult<AuditRecord> searchAccess(ComplianceAccessFilter filter, int page, int size);

    /** Same constraints as {@link #searchAccess}, uncapped by page, bounded by {@code limit}. */
    List<AuditRecord> findAccess(ComplianceAccessFilter filter, int limit);

    /** Totals over the whole matching access set, independent of paging. */
    AccessSummary summarizeAccess(ComplianceAccessFilter filter);
}
