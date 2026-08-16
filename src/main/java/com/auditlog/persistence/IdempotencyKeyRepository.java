package com.auditlog.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface IdempotencyKeyRepository extends Repository<IdempotencyKeyEntity, Long> {

    Optional<IdempotencyKeyEntity> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    IdempotencyKeyEntity save(IdempotencyKeyEntity record);
}
