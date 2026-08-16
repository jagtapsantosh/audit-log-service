package com.auditlog.persistence;

import com.auditlog.domain.IdempotencyRecord;
import com.auditlog.domain.IdempotencyStore;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyKeyRepository repository;

    public JpaIdempotencyStore(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<IdempotencyRecord> find(String clientId, String key) {
        return repository.findByClientIdAndIdempotencyKey(clientId, key).map(entity -> new IdempotencyRecord(
                entity.getClientId(),
                entity.getIdempotencyKey(),
                entity.getRequestHash(),
                entity.getAuditRecordId(),
                entity.getCreatedAt()));
    }

    @Override
    public void save(IdempotencyRecord record) {
        repository.save(new IdempotencyKeyEntity(
                record.clientId(),
                record.key(),
                record.requestHash(),
                record.auditRecordId(),
                record.createdAt()));
    }
}
