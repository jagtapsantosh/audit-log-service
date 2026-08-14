package com.auditlog.domain;

import java.time.Instant;
import java.util.List;

/**
 * Self-contained export bundle.
 *
 * <p>{@code bundleHash} is SHA-256 over the canonical form of this document with {@code bundleHash}
 * itself removed. It answers exactly one question — "has this file changed since the service
 * produced it?" — which is what the assignment asks a recipient to be able to check independently.
 * Full chain integrity remains a server-side {@code GET /audit/verify}, because the plaintext
 * payload behind a redaction never leaves the database.
 */
public record ExportBundle(
        String exportVersion,
        Instant exportedAt,
        ExportFilter filter,
        String genesisHash,
        List<ExportRecord> records,
        String bundleHash
) {

    public static final String CURRENT_VERSION = "1.0";

    /** The same bundle with its hash filled in; the hash is computed over the document without it. */
    public ExportBundle sealedWith(String bundleHash) {
        return new ExportBundle(exportVersion, exportedAt, filter, genesisHash, records, bundleHash);
    }
}
