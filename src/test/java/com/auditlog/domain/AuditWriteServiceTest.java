package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditWriteServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T11:37:00Z");

    @Mock
    private AuditRecordStore store;

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final HashChainService hashChainService = new HashChainService(canonicalJson);

    private AuditWriteService writeService;

    @BeforeEach
    void setUp() {
        writeService = new AuditWriteService(
                store,
                hashChainService,
                canonicalJson,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("first record starts at sequence 1 and links to the genesis value")
    void firstRecordUsesGenesis() {
        when(store.findChainHead()).thenReturn(Optional.empty());
        when(store.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuditRecord appended = writeService.append(event(Instant.parse("2026-08-14T11:30:00Z"), "{\"ip\":\"10.0.0.1\"}"));

        assertThat(appended.sequence()).isEqualTo(1);
        assertThat(appended.previousHash()).isEqualTo(HashChainService.GENESIS_HASH);
        assertThat(appended.recordedAt()).isEqualTo(NOW);
        assertThat(appended.contentHash()).isEqualTo(hashChainService.contentHash(appended.chainInput()));
    }

    @Test
    @DisplayName("the chain lock is taken before the head is read")
    void locksBeforeReadingHead() {
        when(store.findChainHead()).thenReturn(Optional.empty());
        when(store.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        writeService.append(event(NOW, null));

        // Reading the head without the lock is exactly how two writers end up sharing a predecessor.
        InOrder order = inOrder(store);
        order.verify(store).lockChain();
        order.verify(store).findChainHead();
        order.verify(store).append(any());
    }

    @Test
    void subsequentRecordLinksToStoredHeadHash() {
        AuditRecord head = new AuditRecord(
                7L,
                7,
                "USER_LOGIN",
                "user-1",
                "SESSION",
                "s-1",
                canonicalJson.emptyObject(),
                NOW.minus(Duration.ofMinutes(1)),
                NOW.minus(Duration.ofMinutes(1)),
                "a".repeat(64),
                "b".repeat(64));
        when(store.findChainHead()).thenReturn(Optional.of(head));
        when(store.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuditRecord appended = writeService.append(event(NOW, null));

        assertThat(appended.sequence()).isEqualTo(8);
        assertThat(appended.previousHash()).isEqualTo(head.contentHash());
    }

    @Test
    void storesMissingPayloadAsEmptyObject() {
        when(store.findChainHead()).thenReturn(Optional.empty());
        when(store.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        writeService.append(event(NOW, null));

        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(store).append(captor.capture());
        assertThat(canonicalJson.serialize(captor.getValue().payload())).isEqualTo("{}");
    }

    @Test
    @DisplayName("an arbitrarily old occurredAt is accepted: producers may be offline or batched")
    void acceptsBackdatedEvents() {
        when(store.findChainHead()).thenReturn(Optional.empty());
        when(store.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuditRecord appended = writeService.append(event(NOW.minus(Duration.ofDays(400)), null));

        assertThat(appended.occurredAt()).isEqualTo(NOW.minus(Duration.ofDays(400)));
        assertThat(appended.recordedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsOccurredAtBeyondAllowedFutureSkew() {
        assertThatThrownBy(() -> writeService.append(event(NOW.plus(Duration.ofMinutes(6)), null)))
                .isInstanceOf(InvalidAuditEventException.class)
                .extracting(ex -> ((InvalidAuditEventException) ex).code())
                .isEqualTo("OCCURRED_AT_IN_FUTURE");

        verify(store, never()).append(any());
    }

    @Test
    void allowsSmallClockSkewWithinTheWindow() {
        when(store.findChainHead()).thenReturn(Optional.empty());
        when(store.append(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuditRecord appended = writeService.append(event(NOW.plus(Duration.ofMinutes(4)), null));

        assertThat(appended.occurredAt()).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
    }

    @Test
    void rejectsNonObjectPayload() {
        assertThatThrownBy(() -> writeService.append(event(NOW, "[1,2,3]")))
                .isInstanceOf(InvalidAuditEventException.class)
                .extracting(ex -> ((InvalidAuditEventException) ex).code())
                .isEqualTo("PAYLOAD_NOT_OBJECT");
    }

    @Test
    void rejectsOversizedPayload() {
        String oversized = "{\"blob\":\"" + "x".repeat(AuditWriteService.MAX_PAYLOAD_BYTES) + "\"}";

        assertThatThrownBy(() -> writeService.append(event(NOW, oversized)))
                .isInstanceOf(InvalidAuditEventException.class)
                .extracting(ex -> ((InvalidAuditEventException) ex).code())
                .isEqualTo("PAYLOAD_TOO_LARGE");

        verify(store, never()).append(any());
    }

    private NewAuditEvent event(Instant occurredAt, String payloadJson) {
        JsonNode payload = payloadJson == null ? null : canonicalJson.parse(payloadJson);
        return new NewAuditEvent("USER_LOGIN", "user-123", "SESSION", "sess-abc", payload, occurredAt);
    }
}
