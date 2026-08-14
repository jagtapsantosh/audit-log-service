package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RedactionServiceTest {

    private static final long RECORD_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00.123456789Z");
    private static final String PAYLOAD =
            "{\"accountNumber\":\"1234-5678\",\"customer\":{\"ssn\":\"111-22-3333\"},\"ip\":\"10.0.0.1\"}";

    @Mock
    private AuditRecordStore recordStore;

    @Mock
    private AuditRedactionStore redactionStore;

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final RedactionOverlay overlay = new RedactionOverlay();

    private RedactionService redactionService;

    @BeforeEach
    void setUp() {
        redactionService = new RedactionService(recordStore, redactionStore, overlay,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
    }

    @Test
    void redactsAPathAndReturnsTheMaskedView() {
        stubRecord();
        stubNoExistingRedactions();
        stubSaveAll();

        AuditRecordView view = redactionService.redact(command(List.of("accountNumber"), "GDPR"));

        assertThat(view.visiblePayload().get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(view.visiblePayload().get("ip").asText()).isEqualTo("10.0.0.1");
        assertThat(view.redactedFields()).containsExactly("accountNumber");
        verify(recordStore).markHasRedactions(RECORD_ID);
    }

    @Test
    void neverTouchesThePayloadTheChainHashes() {
        stubRecord();
        stubNoExistingRedactions();
        stubSaveAll();

        AuditRecordView view = redactionService.redact(command(List.of("accountNumber"), "GDPR"));

        assertThat(view.record().payload().get("accountNumber").asText()).isEqualTo("1234-5678");
        assertThat(view.record().contentHash()).isEqualTo("a".repeat(64));
    }

    @Test
    void recordsTheOperatorAndReasonAtMicrosecondPrecision() {
        stubRecord();
        stubNoExistingRedactions();
        stubSaveAll();

        redactionService.redact(command(List.of("customer.ssn"), "GDPR erasure request 42"));

        ArgumentCaptor<List<Redaction>> saved = ArgumentCaptor.captor();
        verify(redactionStore).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(redaction -> {
            assertThat(redaction.auditRecordId()).isEqualTo(RECORD_ID);
            assertThat(redaction.fieldPath()).isEqualTo("customer.ssn");
            assertThat(redaction.redactedBy()).isEqualTo("ops-admin");
            assertThat(redaction.reason()).isEqualTo("GDPR erasure request 42");
            assertThat(redaction.redactedAt()).isEqualTo(Instant.parse("2026-08-14T12:00:00.123456Z"));
        });
    }

    @Test
    void redactingTheSamePathTwiceIsANoOp() {
        stubRecord();
        when(redactionStore.findByRecordId(RECORD_ID)).thenReturn(List.of(
                new Redaction(1L, RECORD_ID, "accountNumber", NOW, "ops-admin", "GDPR")));

        AuditRecordView view = redactionService.redact(command(List.of("accountNumber"), "GDPR again"));

        assertThat(view.redactedFields()).containsExactly("accountNumber");
        verify(redactionStore, never()).saveAll(any());
        verify(recordStore, never()).markHasRedactions(anyLong());
    }

    @Test
    void addsOnlyThePathsThatAreNotAlreadyRedacted() {
        stubRecord();
        when(redactionStore.findByRecordId(RECORD_ID)).thenReturn(List.of(
                new Redaction(1L, RECORD_ID, "accountNumber", NOW, "ops-admin", "GDPR")));
        stubSaveAll();

        AuditRecordView view = redactionService.redact(
                command(List.of("accountNumber", "customer.ssn"), "GDPR"));

        ArgumentCaptor<List<Redaction>> saved = ArgumentCaptor.captor();
        verify(redactionStore).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Redaction::fieldPath).containsExactly("customer.ssn");
        assertThat(view.redactedFields()).containsExactly("accountNumber", "customer.ssn");
    }

    @Test
    void deduplicatesRepeatedPathsInOneRequest() {
        stubRecord();
        stubNoExistingRedactions();
        stubSaveAll();

        redactionService.redact(command(List.of("accountNumber", "accountNumber"), "GDPR"));

        ArgumentCaptor<List<Redaction>> saved = ArgumentCaptor.captor();
        verify(redactionStore).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
    }

    @Test
    void rejectsAPathThatIsNotInThePayload() {
        stubRecord();

        assertThatThrownBy(() -> redactionService.redact(command(List.of("nope"), "GDPR")))
                .isInstanceOf(InvalidRedactionException.class)
                .satisfies(thrown -> assertThat(((InvalidRedactionException) thrown).code())
                        .isEqualTo("UNKNOWN_FIELD_PATH"));

        verify(redactionStore, never()).saveAll(any());
    }

    @Test
    void rejectsWhenAnyPathInTheRequestIsUnknown() {
        stubRecord();

        assertThatThrownBy(() -> redactionService.redact(
                command(List.of("accountNumber", "nope"), "GDPR")))
                .isInstanceOf(InvalidRedactionException.class);

        // All-or-nothing: a partially applied redaction would be confusing to audit.
        verify(redactionStore, never()).saveAll(any());
    }

    @Test
    void rejectsMalformedPathsBeforeLoadingTheRecord() {
        assertThatThrownBy(() -> redactionService.redact(command(List.of("items[0].id"), "GDPR")))
                .isInstanceOf(InvalidRedactionException.class)
                .satisfies(thrown -> assertThat(((InvalidRedactionException) thrown).code())
                        .isEqualTo("INVALID_FIELD_PATH"));

        verifyNoInteractions(recordStore, redactionStore);
    }

    @Test
    void rejectsAnEmptyFieldPathList() {
        assertThatThrownBy(() -> redactionService.redact(command(List.of(), "GDPR")))
                .isInstanceOf(InvalidRedactionException.class)
                .satisfies(thrown -> assertThat(((InvalidRedactionException) thrown).code())
                        .isEqualTo("FIELD_PATHS_REQUIRED"));
    }

    @Test
    void rejectsAnUnboundedNumberOfPaths() {
        List<String> tooMany = IntStream.range(0, 51).mapToObj(i -> "field" + i).toList();

        assertThatThrownBy(() -> redactionService.redact(command(tooMany, "GDPR")))
                .isInstanceOf(InvalidRedactionException.class)
                .satisfies(thrown -> assertThat(((InvalidRedactionException) thrown).code())
                        .isEqualTo("TOO_MANY_FIELD_PATHS"));
    }

    @Test
    void rejectsAMissingOperatorIdentity() {
        assertThatThrownBy(() -> redactionService.redact(
                new RedactionCommand(RECORD_ID, List.of("accountNumber"), "GDPR", " ")))
                .isInstanceOf(InvalidRedactionException.class)
                .satisfies(thrown -> assertThat(((InvalidRedactionException) thrown).code())
                        .isEqualTo("REDACTED_BY_REQUIRED"));
    }

    @Test
    void failsWhenTheRecordDoesNotExist() {
        when(recordStore.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> redactionService.redact(command(List.of("accountNumber"), "GDPR")))
                .isInstanceOf(AuditRecordNotFoundException.class);
    }

    @Test
    void viewAppliesExistingRedactions() {
        stubRecord();
        when(redactionStore.findByRecordId(RECORD_ID)).thenReturn(List.of(
                new Redaction(1L, RECORD_ID, "customer.ssn", NOW, "ops-admin", "GDPR")));

        AuditRecordView view = redactionService.view(RECORD_ID);

        assertThat(view.visiblePayload().get("customer").get("ssn").asText()).isEqualTo("[REDACTED]");
        assertThat(view.redactedFields()).containsExactly("customer.ssn");
    }

    private RedactionCommand command(List<String> fieldPaths, String reason) {
        return new RedactionCommand(RECORD_ID, fieldPaths, reason, "ops-admin");
    }

    private void stubRecord() {
        when(recordStore.findById(RECORD_ID)).thenReturn(Optional.of(new AuditRecord(
                RECORD_ID, 3L, "ACCOUNT_VIEWED", "user-1", "CLIENT_ACCOUNT", "acct-1",
                canonicalJson.parse(PAYLOAD), NOW, NOW, "a".repeat(64), "b".repeat(64))));
    }

    private void stubNoExistingRedactions() {
        when(redactionStore.findByRecordId(RECORD_ID)).thenReturn(List.of());
    }

    private void stubSaveAll() {
        when(redactionStore.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
