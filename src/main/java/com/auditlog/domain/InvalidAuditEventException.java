package com.auditlog.domain;

/** A caller-supplied event that fails a domain rule (clock skew, payload shape, payload size). */
public class InvalidAuditEventException extends RuntimeException {

    private final String code;

    public InvalidAuditEventException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
