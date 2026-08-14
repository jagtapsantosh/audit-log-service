package com.auditlog.domain;

/** A compliance request that names an inverted time range, an unknown export format, or too many rows. */
public class InvalidComplianceRequestException extends RuntimeException {

    private final String code;

    public InvalidComplianceRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
