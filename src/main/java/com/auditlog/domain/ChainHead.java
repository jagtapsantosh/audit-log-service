package com.auditlog.domain;

/**
 * The newest {@code (sequence, contentHash)} this service published. Verify compares it to the
 * current table so deleting the tail of {@code audit_records} is visible.
 */
public record ChainHead(long sequence, String contentHash) {
}
