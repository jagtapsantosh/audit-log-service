package com.auditlog.domain;

import java.util.Optional;

/**
 * Outcome of a full chain walk. An empty chain is intact by definition: there is nothing that could
 * have been altered.
 */
public record ChainVerificationResult(boolean intact, long totalRecords, ChainViolation firstViolation) {

    public static ChainVerificationResult intact(long totalRecords) {
        return new ChainVerificationResult(true, totalRecords, null);
    }

    public static ChainVerificationResult broken(long totalRecords, ChainViolation violation) {
        return new ChainVerificationResult(false, totalRecords, violation);
    }

    public Optional<ChainViolation> violation() {
        return Optional.ofNullable(firstViolation);
    }

    /**
     * The first inconsistency found, walking by ascending sequence. {@code expectedHash} and
     * {@code actualHash} are null for violations that are not about a hash value.
     */
    public record ChainViolation(
            long sequence,
            Long recordId,
            ViolationType violationType,
            String expectedHash,
            String actualHash,
            String detail
    ) {
    }

    public enum ViolationType {
        /** Stored content hash does not match a re-hash of the stored fields: the record changed. */
        CONTENT_HASH_MISMATCH,
        /** The record does not point at its predecessor's content hash: a link was rewritten. */
        PREVIOUS_HASH_BREAK,
        /** Sequence numbers are not contiguous: a record was removed or inserted out of band. */
        SEQUENCE_GAP
    }
}
