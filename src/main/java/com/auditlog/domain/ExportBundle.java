package com.auditlog.domain;

import java.time.Instant;
import java.util.List;

/**
 * Self-contained export bundle.
 *
 * <p>{@code bundleHash} is SHA-256 over the canonical form of this document with {@code bundleHash}
 * itself removed. It answers exactly one question — "has this file changed since the service
 * produced it?" — which is what the assignment asks a recipient to be able to check independently.
 * {@code bundleSignature} is HMAC-SHA256 of that hash with a service signing key, so a recipient
 * who has the key can reject a file that was edited and then re-hashed. Full chain integrity
 * remains a server-side {@code GET /audit/verify}, because the plaintext payload behind a redaction
 * never leaves the database.
 */
public record ExportBundle(
        String exportVersion,
        Instant exportedAt,
        ExportFilter filter,
        String genesisHash,
        List<ExportRecord> records,
        String bundleHash,
        String bundleSignature
) {

    public static final String CURRENT_VERSION = "1.0";

    /** The same bundle with its hash and signature filled in. Both are omitted from the hash pre-image. */
    public ExportBundle sealedWith(String bundleHash, String bundleSignature) {
        return new ExportBundle(
                exportVersion, exportedAt, filter, genesisHash, records, bundleHash, bundleSignature);
    }
}
