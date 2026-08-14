package com.auditlog.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends the bare {@link Repository} marker rather than {@code JpaRepository} on purpose: only the
 * methods declared here exist, so no update or delete operation is reachable through Spring Data.
 */
public interface AuditRecordRepository extends Repository<AuditRecordEntity, Long> {

    AuditRecordEntity save(AuditRecordEntity record);

    long count();

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
            Pageable pageable);
}
