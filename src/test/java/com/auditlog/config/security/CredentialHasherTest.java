package com.auditlog.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CredentialHasherTest {

    @Test
    void hashesAreDeterministicAndComparedInConstantTime() {
        String pepper = "dev-only-pepper-not-for-production";
        String secret = "als_ingest_dev_key_do_not_use_in_prod";
        String hash = CredentialHasher.sha256(secret, pepper);

        assertEquals("21b31abc1720e3c98203c46e412b12d8e264718a1ac0c57980df1a0672b7be85", hash);
        assertTrue(CredentialHasher.matches(secret, pepper, hash));
        assertFalse(CredentialHasher.matches("wrong", pepper, hash));
    }
}
