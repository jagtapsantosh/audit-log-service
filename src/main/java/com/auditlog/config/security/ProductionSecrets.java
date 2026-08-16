package com.auditlog.config.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Production must not boot on the documented local fallbacks. Local / test profiles keep those
 * defaults so the README script still works.
 */
public final class ProductionSecrets {

    public static final String DEV_PEPPER = "dev-only-pepper-not-for-production";
    public static final String DEV_JWT_SECRET = "dev-only-hmac-secret-must-be-at-least-32-bytes!";
    public static final String DEV_EXPORT_SIGNING_KEY = "dev-only-export-signing-key-not-for-production!!";

    private ProductionSecrets() {
    }

    public static void assertSafe(String pepper, String jwtSecret, String exportSigningKey) {
        List<String> failures = new ArrayList<>();
        rejectDefault(failures, "audit.security.pepper", pepper, DEV_PEPPER);
        rejectDefault(failures, "audit.security.jwt.secret", jwtSecret, DEV_JWT_SECRET);
        rejectDefault(failures, "audit.security.export-signing-key", exportSigningKey, DEV_EXPORT_SIGNING_KEY);
        if (jwtSecret != null && jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            failures.add("audit.security.jwt.secret must be at least 32 bytes");
        }
        if (exportSigningKey != null && exportSigningKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            failures.add("audit.security.export-signing-key must be at least 32 bytes");
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Production profile refused to start with unsafe secrets: "
                            + String.join("; ", failures)
                            + ". Set AUDIT_SECURITY_PEPPER, AUDIT_JWT_SECRET, and "
                            + "AUDIT_EXPORT_SIGNING_KEY to values that are not the documented "
                            + "dev-only fallbacks.");
        }
    }

    private static void rejectDefault(List<String> failures, String name, String value, String forbidden) {
        if (value == null || value.isBlank()) {
            failures.add(name + " is missing");
            return;
        }
        if (value.equals(forbidden) || value.startsWith("dev-only-")) {
            failures.add(name + " is a documented development fallback");
        }
    }
}
