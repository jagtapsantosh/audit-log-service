package com.auditlog.persistence;

import com.auditlog.domain.RecordStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends the bare {@link Repository} marker rather than {@code JpaRepository} on purpose: only the
 * methods declared here exist, so no delete and no general-purpose update is reachable through Spring
 * Data.
 *
 * <p>The two {@link Modifying} statements below are the only writes other than {@code save}. Both
 * name their target columns explicitly, and neither can reach a column that participates in the hash
 * pre-image, so the append-only guarantee over hashed content is unchanged by Scenario B.
 */
public interface AuditRecordRepository extends Repository<AuditRecordEntity, Long> {

    AuditRecordEntity save(AuditRecordEntity record);

    long count();

    Optional<AuditRecordEntity> findById(long id);

    Optional<AuditRecordEntity> findFirstByOrderBySequenceNumDesc();

    List<AuditRecordEntity> findBySequenceNumGreaterThanOrderBySequenceNumAsc(long sequenceNum, Pageable pageable);

    /**
     * Transaction-scoped advisory lock guarding sequence assignment. Released automatically when the
     * appending transaction commits or rolls back.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS chain_lock", nativeQuery = true)
    Integer acquireChainLock(@Param("key") long key);

    /**
     * Optional filters are expressed as {@code column = COALESCE(:param, column)} rather than
     * {@code :param IS NULL OR ...}: PostgreSQL cannot infer the type of a standalone parameter in
     * {@code ? IS NULL} and rejects the statement. Every filtered column is NOT NULL, so a null
     * parameter degrades to a tautology and the filter drops out.
     */
    @Query("""
            SELECT r FROM AuditRecordEntity r
            WHERE r.actorId = COALESCE(:actorId, r.actorId)
              AND r.resourceType = COALESCE(:resourceType, r.resourceType)
              AND r.resourceId = COALESCE(:resourceId, r.resourceId)
              AND r.eventType = COALESCE(:eventType, r.eventType)
              AND r.occurredAt >= COALESCE(:occurredFrom, r.occurredAt)
              AND r.occurredAt <= COALESCE(:occurredTo, r.occurredAt)
              AND r.recordedAt >= COALESCE(:recordedFrom, r.recordedAt)
              AND r.recordedAt <= COALESCE(:recordedTo, r.recordedAt)
              AND r.status IN :statuses
            """)
    Page<AuditRecordEntity> search(
            @Param("actorId") String actorId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("eventType") String eventType,
            @Param("occurredFrom") Instant occurredFrom,
            @Param("occurredTo") Instant occurredTo,
            @Param("recordedFrom") Instant recordedFrom,
            @Param("recordedTo") Instant recordedTo,
            @Param("statuses") Collection<RecordStatus> statuses,
            Pageable pageable);

    /**
     * Export slice. Archived records are included: they are still evidence, and a bundle that
     * silently dropped them would misrepresent the subject's history.
     */
    @Query("""
            SELECT r FROM AuditRecordEntity r
            WHERE r.actorId = COALESCE(:actorId, r.actorId)
              AND r.resourceType = COALESCE(:resourceType, r.resourceType)
              AND r.resourceId = COALESCE(:resourceId, r.resourceId)
            ORDER BY r.sequenceNum ASC
            """)
    List<AuditRecordEntity> findForExport(
            @Param("actorId") String actorId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            Pageable pageable);

    /**
     * Retention sweep. Touches {@code status} and {@code archived_at} only, and only for rows that
     * are still ACTIVE, so a second sweep is a no-op and an archived record's timestamp is never
     * rewritten.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuditRecordEntity r
               SET r.status = :archivedStatus, r.archivedAt = :archivedAt
             WHERE r.recordedAt < :cutoff
               AND r.status = :activeStatus
            """)
    int archiveRecordedBefore(
            @Param("cutoff") Instant cutoff,
            @Param("archivedAt") Instant archivedAt,
            @Param("activeStatus") RecordStatus activeStatus,
            @Param("archivedStatus") RecordStatus archivedStatus);

    /** Sets the cached {@code has_redactions} flag after an overlay row is written. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AuditRecordEntity r SET r.hasRedactions = true WHERE r.id = :id")
    int markHasRedactions(@Param("id") long id);

    /**
     * Scenario C access slice. Resource type and event types are passed in from the adapter, which
     * hard-wires them to {@code AccessScope} so a caller cannot widen the report. Archived rows are
     * included; there is no status predicate.
     */
    @Query("""
            SELECT r FROM AuditRecordEntity r
            WHERE r.resourceType = :resourceType
              AND r.eventType IN :eventTypes
              AND r.actorId = COALESCE(:actorId, r.actorId)
              AND r.resourceId = COALESCE(:resourceId, r.resourceId)
              AND r.occurredAt >= COALESCE(:occurredFrom, r.occurredAt)
              AND r.occurredAt <= COALESCE(:occurredTo, r.occurredAt)
            """)
    Page<AuditRecordEntity> searchAccess(
            @Param("resourceType") String resourceType,
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("actorId") String actorId,
            @Param("resourceId") String resourceId,
            @Param("occurredFrom") Instant occurredFrom,
            @Param("occurredTo") Instant occurredTo,
            Pageable pageable);

    @Query("""
            SELECT r FROM AuditRecordEntity r
            WHERE r.resourceType = :resourceType
              AND r.eventType IN :eventTypes
              AND r.actorId = COALESCE(:actorId, r.actorId)
              AND r.resourceId = COALESCE(:resourceId, r.resourceId)
              AND r.occurredAt >= COALESCE(:occurredFrom, r.occurredAt)
              AND r.occurredAt <= COALESCE(:occurredTo, r.occurredAt)
            ORDER BY r.sequenceNum ASC
            """)
    List<AuditRecordEntity> findAccess(
            @Param("resourceType") String resourceType,
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("actorId") String actorId,
            @Param("resourceId") String resourceId,
            @Param("occurredFrom") Instant occurredFrom,
            @Param("occurredTo") Instant occurredTo,
            Pageable pageable);

    @Query("""
            SELECT new com.auditlog.persistence.AccessSummaryRow(
                    COUNT(r), COUNT(DISTINCT r.actorId), MIN(r.occurredAt), MAX(r.occurredAt))
              FROM AuditRecordEntity r
             WHERE r.resourceType = :resourceType
               AND r.eventType IN :eventTypes
               AND r.actorId = COALESCE(:actorId, r.actorId)
               AND r.resourceId = COALESCE(:resourceId, r.resourceId)
               AND r.occurredAt >= COALESCE(:occurredFrom, r.occurredAt)
               AND r.occurredAt <= COALESCE(:occurredTo, r.occurredAt)
            """)
    AccessSummaryRow summarizeAccess(
            @Param("resourceType") String resourceType,
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("actorId") String actorId,
            @Param("resourceId") String resourceId,
            @Param("occurredFrom") Instant occurredFrom,
            @Param("occurredTo") Instant occurredTo);
}
