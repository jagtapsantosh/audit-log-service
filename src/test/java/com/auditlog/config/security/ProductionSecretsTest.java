package com.auditlog.config.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductionSecretsTest {

    private static final String SAFE = "production-secret-value-that-is-long-enough!!";

    @Test
    void acceptsNonDefaultSecrets() {
        assertThatCode(() -> ProductionSecrets.assertSafe(SAFE, SAFE, SAFE)).doesNotThrowAnyException();
    }

    @Test
    void rejectsDocumentedDevPepper() {
        assertThatThrownBy(() -> ProductionSecrets.assertSafe(ProductionSecrets.DEV_PEPPER, SAFE, SAFE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pepper");
    }

    @Test
    void rejectsDocumentedDevJwtSecret() {
        assertThatThrownBy(() -> ProductionSecrets.assertSafe(SAFE, ProductionSecrets.DEV_JWT_SECRET, SAFE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void rejectsMissingExportKey() {
        assertThatThrownBy(() -> ProductionSecrets.assertSafe(SAFE, SAFE, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("export-signing-key");
    }

    @Test
    void rejectsShortJwtSecret() {
        assertThatThrownBy(() -> ProductionSecrets.assertSafe(SAFE, "too-short-to-be-an-hmac-key", SAFE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
