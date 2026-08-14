package com.auditlog.domain;

/** An export request that names no subject, or one whose result set is too large to bundle. */
public class InvalidExportRequestException extends RuntimeException {

    private final String code;

    public InvalidExportRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
