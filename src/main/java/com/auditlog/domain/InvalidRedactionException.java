package com.auditlog.domain;

/** A redaction request that cannot be satisfied: bad path syntax, unknown path, or no paths. */
public class InvalidRedactionException extends RuntimeException {

    private final String code;

    public InvalidRedactionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
