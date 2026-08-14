package com.auditlog.persistence;

import com.auditlog.domain.AuditQueryFilter;
import com.auditlog.domain.AuditRecord;
import com.auditlog.domain.AuditRecordStore;
import com.auditlog.domain.CanonicalJson;
import com.auditlog.domain.ExportFilter;
import com.auditlog.domain.PageResult;
import com.auditlog.domain.RecordStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * Adapter between the domain chain and JPA. Payload text is canonicalized on the way in and
 * re-canonicalized on the way out, because {@code jsonb} does not preserve key order or number
 * formatting.
 */
@Repository
public class JpaAuditRecordStore implements AuditRecordStore {

    /** Arbitrary but fixed advisory-lock key identifying the single global chain. */
    private static final long CHAIN_LOCK_KEY = 4_021_115L;

    private static final Sort CHAIN_ORDER = Sort.by(Sort.Direction.ASC, "sequenceNum");

    private final AuditRecordRepository repository;
    private final CanonicalJson canonicalJson;

    public JpaAuditRecordStore(AuditRecordRepository repository, CanonicalJson canonicalJson) {
        this.repository = repository;
        this.canonicalJson = canonicalJson;
    }

    @Override
    public void lockChain() {
        repository.acquireChainLock(CHAIN_LOCK_KEY);
    }

    @Override
    public Optional<AuditRecord> findChainHead() {
        return repository.findFirstByOrderBySequenceNumDesc().map(this::toDomain);
    }

    @Override
    public AuditRecord append(AuditRecord record) {
        AuditRecordEntity entity = new AuditRecordEntity(
                record.sequence(),
                record.eventType(),
                record.actorId(),
                record.resourceType(),
                record.resourceId(),
                canonicalJson.serialize(record.payload()),
                record.occurredAt(),
                record.recordedAt(),
                record.contentHash(),
                record.previousHash());
        return toDomain(repository.save(entity));
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public Optional<AuditRecord> findById(long id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public PageResult<AuditRecord> search(AuditQueryFilter filter, int page, int size) {
        Page<AuditRecordEntity> result = repository.search(
                filter.actorId(),
                filter.resourceType(),
                filter.resourceId(),
                filter.eventType(),
                filter.occurredFrom(),
                filter.occurredTo(),
                filter.recordedFrom(),
                filter.recordedTo(),
                filter.statuses(),
                PageRequest.of(page, size, CHAIN_ORDER));
        return PageResult.of(
                result.getContent().stream().map(this::toDomain).toList(),
                page,
                size,
                result.getTotalElements());
    }

    @Override
    public List<AuditRecord> findSliceAfter(long exclusiveSequence, int limit) {
        return repository
                .findBySequenceNumGreaterThanOrderBySequenceNumAsc(
                        exclusiveSequence, PageRequest.ofSize(limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public int archiveRecordedBefore(Instant cutoff, Instant archivedAt) {
        return repository.archiveRecordedBefore(
                cutoff, archivedAt, RecordStatus.ACTIVE, RecordStatus.ARCHIVED);
    }

    @Override
    public void markHasRedactions(long recordId) {
        repository.markHasRedactions(recordId);
    }

    @Override
    public List<AuditRecord> findForExport(ExportFilter filter, int limit) {
        return repository.findForExport(
                        blankToNull(filter.actorId()),
                        blankToNull(filter.resourceType()),
                        blankToNull(filter.resourceId()),
                        PageRequest.ofSize(limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private AuditRecord toDomain(AuditRecordEntity entity) {
        return new AuditRecord(
                entity.getId(),
                entity.getSequenceNum(),
                entity.getEventType(),
                entity.getActorId(),
                entity.getResourceType(),
                entity.getResourceId(),
                canonicalJson.parse(entity.getPayload()),
                entity.getOccurredAt(),
                entity.getRecordedAt(),
                entity.getContentHash(),
                entity.getPreviousHash(),
                entity.getStatus(),
                entity.getArchivedAt(),
                entity.isHasRedactions());
    }
}
