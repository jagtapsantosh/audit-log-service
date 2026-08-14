package com.auditlog.domain;

/**
 * Retention state of a record. Archiving is a soft state change only: the row, its payload, and its
 * hashes stay exactly as written, so an archived record is still a full link in the chain.
 */
public enum RecordStatus {

    /** Inside the retention window; returned by queries by default. */
    ACTIVE,

    /** Past the retention window. Hidden from queries unless asked for, but still verified. */
    ARCHIVED
}
