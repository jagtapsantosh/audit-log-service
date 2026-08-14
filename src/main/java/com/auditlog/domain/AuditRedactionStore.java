package com.auditlog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistence port for redaction overlay rows. Append-only like the log itself: a redaction is a new
 * row, and nothing here can rewrite or remove one.
 */
public interface AuditRedactionStore {

    List<Redaction> findByRecordId(long recordId);

    /** Batch lookup so a page of results costs one query instead of one per row. */
    Map<Long, List<Redaction>> findByRecordIds(Collection<Long> recordIds);

    List<Redaction> saveAll(List<Redaction> redactions);
}
