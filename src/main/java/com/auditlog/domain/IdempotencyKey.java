package com.auditlog.domain;

/**
 * Caller-supplied replay token for {@code POST /audit/events}. Optional: writes without a key
 * always append. When present, the same caller + key + body returns the original record; a
 * different body with the same key is a conflict.
 */
public record IdempotencyKey(String clientId, String key) {

    public static final int MAX_LENGTH = 128;

    /**
     * @param rawKey {@code null} when the header was omitted (append normally)
     */
    public static IdempotencyKey parse(String clientId, String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String key = rawKey.trim();
        if (key.isEmpty() || key.length() > MAX_LENGTH || !key.matches("[A-Za-z0-9._\\-]+")) {
            throw new InvalidAuditEventException(
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1–" + MAX_LENGTH
                            + " characters of A–Z, a–z, 0–9, '.', '_' or '-'");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidAuditEventException(
                    "IDEMPOTENCY_CLIENT_REQUIRED",
                    "Idempotency-Key requires an authenticated client identity");
        }
        return new IdempotencyKey(clientId, key);
    }
}
