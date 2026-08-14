package com.auditlog.domain;

/** Raised when an operation names a record id the log does not contain. */
public class AuditRecordNotFoundException extends RuntimeException {

    public AuditRecordNotFoundException(long recordId) {
        super("No audit record with id " + recordId);
    }
}
