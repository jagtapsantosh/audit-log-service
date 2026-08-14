package com.auditlog.persistence;

import com.auditlog.domain.AuditRedactionStore;
import com.auditlog.domain.Redaction;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** Adapter between redaction overlay rows and the domain. */
@Repository
public class JpaAuditRedactionStore implements AuditRedactionStore {

    private final AuditRedactionRepository repository;

    public JpaAuditRedactionStore(AuditRedactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Redaction> findByRecordId(long recordId) {
        return repository.findByAuditRecordIdOrderByFieldPathAsc(recordId).stream()
                .map(JpaAuditRedactionStore::toDomain)
                .toList();
    }

    @Override
    public Map<Long, List<Redaction>> findByRecordIds(Collection<Long> recordIds) {
        if (recordIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByAuditRecordIdInOrderByFieldPathAsc(recordIds).stream()
                .map(JpaAuditRedactionStore::toDomain)
                .collect(Collectors.groupingBy(Redaction::auditRecordId));
    }

    @Override
    public List<Redaction> saveAll(List<Redaction> redactions) {
        return redactions.stream()
                .map(redaction -> repository.save(new AuditRedactionEntity(
                        redaction.auditRecordId(),
                        redaction.fieldPath(),
                        redaction.redactedAt(),
                        redaction.redactedBy(),
                        redaction.reason())))
                .map(JpaAuditRedactionStore::toDomain)
                .toList();
    }

    private static Redaction toDomain(AuditRedactionEntity entity) {
        return new Redaction(
                entity.getId(),
                entity.getAuditRecordId(),
                entity.getFieldPath(),
                entity.getRedactedAt(),
                entity.getRedactedBy(),
                entity.getReason());
    }
}
