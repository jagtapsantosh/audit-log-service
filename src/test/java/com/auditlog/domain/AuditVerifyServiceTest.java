package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.auditlog.domain.ChainVerificationResult.ViolationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Chain-walk behaviour, isolated from the database so every violation type is easy to construct. */
@ExtendWith(MockitoExtension.class)
class AuditVerifyServiceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-14T11:30:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-08-14T11:37:00Z");

    @Mock
    private AuditRecordStore store;

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final HashChainService hashChainService = new HashChainService(canonicalJson);

    private AuditVerifyService verifyService;

    @BeforeEach
    void setUp() {
        verifyService = new AuditVerifyService(store, hashChainService, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("an empty chain is intact: there is nothing that could have been altered")
    void emptyChainIsIntact() {
        givenChain(List.of());

        ChainVerificationResult result = verifyService.verify();

        assertThat(result.intact()).isTrue();
        assertThat(result.totalRecords()).isZero();
        assertThat(result.firstViolation()).isNull();
    }

    @Test
    void honestChainIsIntact() {
        givenChain(chainOf(3));

        ChainVerificationResult result = verifyService.verify();

        assertThat(result.intact()).isTrue();
        assertThat(result.totalRecords()).isEqualTo(3);
    }

    @Test
    @DisplayName("payload edited in the data store: content hash no longer matches")
    void detectsAlteredPayload() {
        List<AuditRecord> chain = chainOf(3);
        AuditRecord original = chain.get(1);
        chain.set(1, withPayload(original, "{\"tampered\":true}"));
        givenChain(chain);

        ChainVerificationResult result = verifyService.verify();

        assertThat(result.intact()).isFalse();
        assertThat(result.firstViolation().violationType()).isEqualTo(ViolationType.CONTENT_HASH_MISMATCH);
        assertThat(result.firstViolation().sequence()).isEqualTo(2);
        assertThat(result.firstViolation().actualHash()).isEqualTo(original.contentHash());
        assertThat(result.firstViolation().expectedHash()).isNotEqualTo(original.contentHash());
    }

    @Test
    @DisplayName("relinked record: previousHash no longer points at its predecessor")
    void detectsBrokenLink() {
        List<AuditRecord> chain = chainOf(3);
        chain.set(1, withPreviousHash(chain.get(1), "f".repeat(64)));
        givenChain(chain);

        ChainVerificationResult result = verifyService.verify();

        assertThat(result.firstViolation().violationType()).isEqualTo(ViolationType.PREVIOUS_HASH_BREAK);
        assertThat(result.firstViolation().sequence()).isEqualTo(2);
    }

    @Test
    @DisplayName("removed record: sequence numbers are no longer contiguous")
    void detectsSequenceGap() {
        List<AuditRecord> chain = chainOf(3);
        chain.remove(1);
        givenChain(chain);

        ChainVerificationResult result = verifyService.verify();

        assertThat(result.firstViolation().violationType()).isEqualTo(ViolationType.SEQUENCE_GAP);
        assertThat(result.firstViolation().sequence()).isEqualTo(3);
        assertThat(result.firstViolation().detail()).contains("expected sequence 2");
    }

    @Test
    void reportsOnlyTheFirstViolation() {
        List<AuditRecord> chain = chainOf(4);
        chain.set(1, withPayload(chain.get(1), "{\"tampered\":1}"));
        chain.set(2, withPayload(chain.get(2), "{\"tampered\":2}"));
        givenChain(chain);

        assertThat(verifyService.verify().firstViolation().sequence()).isEqualTo(2);
    }

    /** Stubs keyset paging: return the records whose sequence is past the cursor. */
    private void givenChain(List<AuditRecord> chain) {
        when(store.count()).thenReturn((long) chain.size());
        when(store.findSliceAfter(anyLong(), anyInt())).thenAnswer(invocation -> {
            long cursor = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return chain.stream().filter(record -> record.sequence() > cursor).limit(limit).toList();
        });
    }

    /** Builds an honestly linked chain: each record hashes its own content plus its predecessor. */
    private List<AuditRecord> chainOf(int size) {
        List<AuditRecord> chain = new ArrayList<>();
        String previousHash = HashChainService.GENESIS_HASH;
        for (int i = 1; i <= size; i++) {
            AuditRecord unhashed = new AuditRecord(
                    (long) i,
                    i,
                    "USER_LOGIN",
                    "user-" + i,
                    "SESSION",
                    "sess-" + i,
                    canonicalJson.parse("{\"attempt\":" + i + "}"),
                    OCCURRED_AT,
                    RECORDED_AT,
                    null,
                    previousHash);
            String contentHash = hashChainService.contentHash(unhashed.chainInput());
            chain.add(hashed(unhashed, contentHash));
            previousHash = contentHash;
        }
        return chain;
    }

    private AuditRecord hashed(AuditRecord record, String contentHash) {
        return new AuditRecord(
                record.id(),
                record.sequence(),
                record.eventType(),
                record.actorId(),
                record.resourceType(),
                record.resourceId(),
                record.payload(),
                record.occurredAt(),
                record.recordedAt(),
                contentHash,
                record.previousHash());
    }

    private AuditRecord withPayload(AuditRecord record, String payloadJson) {
        return new AuditRecord(
                record.id(),
                record.sequence(),
                record.eventType(),
                record.actorId(),
                record.resourceType(),
                record.resourceId(),
                canonicalJson.parse(payloadJson),
                record.occurredAt(),
                record.recordedAt(),
                record.contentHash(),
                record.previousHash());
    }

    private AuditRecord withPreviousHash(AuditRecord record, String previousHash) {
        return new AuditRecord(
                record.id(),
                record.sequence(),
                record.eventType(),
                record.actorId(),
                record.resourceType(),
                record.resourceId(),
                record.payload(),
                record.occurredAt(),
                record.recordedAt(),
                record.contentHash(),
                previousHash);
    }
}
