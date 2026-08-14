package com.auditlog.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

/**
 * Like {@link AuditRecordRepository}, this extends the bare {@link Repository} marker so only the
 * methods declared here exist: insert and read, no update, no delete.
 */
public interface AuditRedactionRepository extends Repository<AuditRedactionEntity, Long> {

    AuditRedactionEntity save(AuditRedactionEntity redaction);

    List<AuditRedactionEntity> findByAuditRecordIdOrderByFieldPathAsc(long auditRecordId);

    List<AuditRedactionEntity> findByAuditRecordIdInOrderByFieldPathAsc(Collection<Long> auditRecordIds);
}
