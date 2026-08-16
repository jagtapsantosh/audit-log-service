package com.auditlog.domain;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 over UTF-8 bytes, hex-encoded. Used to sign export bundle hashes. */
public final class HmacSha256 {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSha256() {
    }

    public static String hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(ALGORITHM + " is required but unavailable", e);
        }
    }
}
