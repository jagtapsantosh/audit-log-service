package com.auditlog.api.error;

import java.time.Instant;

public record ErrorResponse(String error, String code, Instant timestamp) {

    public static ErrorResponse of(String error, String code) {
        return new ErrorResponse(error, code, Instant.now());
    }
}
