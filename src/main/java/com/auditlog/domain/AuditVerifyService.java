package com.auditlog.domain;

import com.auditlog.domain.ChainVerificationResult.ChainViolation;
import com.auditlog.domain.ChainVerificationResult.ViolationType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walks the whole chain in sequence order and reports the first inconsistency.
 *
 * <p>The walk is O(n) over stored records and holds only one batch in memory. There are no
 * checkpoints or cached verification state: a checkpoint would itself have to be trusted, and this
 * prototype prefers a slow honest answer over a fast one.
 */
@Service
public class AuditVerifyService {

    /** Keyset batch size: bounds memory without turning the walk into thousands of round trips. */
    private static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(AuditVerifyService.class);

    private final AuditRecordStore store;
    private final HashChainService hashChainService;
    private final Timer verifyTimer;
    private final AtomicInteger lastKnownIntact = new AtomicInteger(1);

    public AuditVerifyService(
            AuditRecordStore store,
            HashChainService hashChainService,
            MeterRegistry meterRegistry
    ) {
        this.store = store;
        this.hashChainService = hashChainService;
        this.verifyTimer = Timer.builder("audit.verify.duration")
                .description("Time to walk the full hash chain")
                .register(meterRegistry);
        meterRegistry.gauge("audit.chain.intact", lastKnownIntact);
    }

    @Transactional(readOnly = true)
    public ChainVerificationResult verify() {
        return verifyTimer.record(this::walk);
    }

    private ChainVerificationResult walk() {
        long totalRecords = store.count();
        long expectedSequence = 1;
        String expectedPreviousHash = HashChainService.GENESIS_HASH;
        long cursor = 0;

        while (true) {
            List<AuditRecord> batch = store.findSliceAfter(cursor, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (AuditRecord record : batch) {
                ChainViolation violation = inspect(record, expectedSequence, expectedPreviousHash);
                if (violation != null) {
                    lastKnownIntact.set(0);
                    log.warn(
                            "Chain verification failed at sequence={} type={}",
                            violation.sequence(),
                            violation.violationType());
                    return ChainVerificationResult.broken(totalRecords, violation);
                }
                expectedSequence = record.sequence() + 1;
                // Chain to the stored hash, not the recomputed one, so a single tampered record is
                // reported once rather than cascading into every successor.
                expectedPreviousHash = record.contentHash();
                cursor = record.sequence();
            }
        }

        lastKnownIntact.set(1);
        return ChainVerificationResult.intact(totalRecords);
    }

    private ChainViolation inspect(AuditRecord record, long expectedSequence, String expectedPreviousHash) {
        if (record.sequence() != expectedSequence) {
            return new ChainViolation(
                    record.sequence(),
                    record.id(),
                    ViolationType.SEQUENCE_GAP,
                    null,
                    null,
                    "expected sequence " + expectedSequence + " but found " + record.sequence());
        }
        if (!expectedPreviousHash.equals(record.previousHash())) {
            return new ChainViolation(
                    record.sequence(),
                    record.id(),
                    ViolationType.PREVIOUS_HASH_BREAK,
                    expectedPreviousHash,
                    record.previousHash(),
                    "previousHash does not match the predecessor's contentHash");
        }
        String recomputed = hashChainService.contentHash(record.chainInput());
        if (!recomputed.equals(record.contentHash())) {
            return new ChainViolation(
                    record.sequence(),
                    record.id(),
                    ViolationType.CONTENT_HASH_MISMATCH,
                    recomputed,
                    record.contentHash(),
                    "stored contentHash does not match a re-hash of the stored record");
        }
        return null;
    }
}
