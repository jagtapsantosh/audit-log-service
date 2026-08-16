package com.auditlog.domain;

/** Same {@code Idempotency-Key} reused with a different request body. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency-Key was reused with a different request body");
    }
}
