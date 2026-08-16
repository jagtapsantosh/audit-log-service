package com.auditlog.domain;

import java.util.Optional;

/** Persistence port for ingest replay keys. Not part of the hash chain. */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(String clientId, String key);

    void save(IdempotencyRecord record);
}
