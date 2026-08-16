package com.auditlog.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface ChainHeadRepository extends Repository<ChainHeadEntity, Short> {

    Optional<ChainHeadEntity> findById(short id);

    ChainHeadEntity save(ChainHeadEntity head);
}
