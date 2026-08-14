package com.auditlog.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one place SHA-256 is computed, shared by the record chain and the export bundle so both are
 * demonstrably the same algorithm over canonical bytes.
 */
public final class Sha256 {

    private static final String ALGORITHM = "SHA-256";

    private Sha256() {
    }

    public static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required but unavailable", e);
        }
    }
}
