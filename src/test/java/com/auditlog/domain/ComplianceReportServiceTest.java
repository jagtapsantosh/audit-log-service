package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ComplianceReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00.123456789Z");
    private static final Instant OCCURRED = Instant.parse("2026-08-14T11:30:00Z");
    private static final Instant RECORDED = Instant.parse("2026-08-14T11:37:00Z");

    @Mock
    private AuditRecordStore recordStore;

    @Mock
    private AuditRedactionStore redactionStore;

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final RedactionOverlay overlay = new RedactionOverlay();

    private ComplianceReportService service;

    @BeforeEach
    void setUp() {
        service = new ComplianceReportService(
                recordStore, redactionStore, overlay, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void emptyChainPinsGenesisAsTheHeadHash() {
        when(recordStore.searchAccess(any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0, 50, 0));
        when(recordStore.summarizeAccess(any())).thenReturn(AccessSummary.empty());
        when(recordStore.findChainHead()).thenReturn(Optional.empty());

        ComplianceReport report = service.report(ComplianceAccessFilter.empty(), null, null);

        assertThat(report.chainHeadHash()).isEqualTo(HashChainService.GENESIS_HASH);
        assertThat(report.summary().totalAccessEvents()).isZero();
        assertThat(report.events()).isEmpty();
        assertThat(UUID.fromString(report.reportId())).isNotNull();
        assertThat(report.generatedAt()).isEqualTo(Instant.parse("2026-08-16T12:00:00.123456Z"));
        assertThat(report.filter().resourceType()).isEqualTo(AccessScope.RESOURCE_TYPE);
        assertThat(report.filter().eventTypes()).isEqualTo(AccessScope.EVENT_TYPES);
        assertThat(report.verificationHint()).isEqualTo(AccessScope.VERIFICATION_HINT);
    }

    @Test
    void chainHeadHashIsTheGlobalHeadNotTheLastAccessEvent() {
        AuditRecord access = access(1L, 1L, "ACCOUNT_VIEWED", "user-1", "acct-1", false);
        AuditRecord laterLogin = record(9L, 9L, "USER_LOGIN", "user-9", "SESSION", "sess-9", false);
        when(recordStore.searchAccess(any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(access), 0, 50, 1));
        when(recordStore.summarizeAccess(any()))
                .thenReturn(new AccessSummary(1, 1, OCCURRED, OCCURRED));
        when(recordStore.findChainHead()).thenReturn(Optional.of(laterLogin));

        ComplianceReport report = service.report(ComplianceAccessFilter.empty(), 0, 50);

        assertThat(report.chainHeadHash()).isEqualTo(laterLogin.contentHash());
        assertThat(report.events()).extracting(view -> view.record().eventType())
                .containsExactly("ACCOUNT_VIEWED");
    }

    @Test
    void summaryIsOverTheWholeMatchSetNotThePage() {
        AuditRecord first = access(1L, 1L, "ACCOUNT_VIEWED", "user-1", "acct-1", false);
        when(recordStore.searchAccess(any(), eq(0), eq(1)))
                .thenReturn(PageResult.of(List.of(first), 0, 1, 4));
        when(recordStore.summarizeAccess(any()))
                .thenReturn(new AccessSummary(4, 2, OCCURRED, OCCURRED.plusSeconds(60)));
        when(recordStore.findChainHead()).thenReturn(Optional.of(first));

        ComplianceReport report = service.report(ComplianceAccessFilter.empty(), 0, 1);

        assertThat(report.events()).hasSize(1);
        assertThat(report.summary().totalAccessEvents()).isEqualTo(4);
        assertThat(report.summary().uniqueActors()).isEqualTo(2);
        assertThat(report.totalElements()).isEqualTo(4);
        assertThat(report.totalPages()).isEqualTo(4);
    }

    @Test
    void appliesTheRedactionOverlayOnReportRows() {
        AuditRecord record = access(1L, 1L, "ACCOUNT_VIEWED", "user-1", "acct-1", true);
        when(recordStore.searchAccess(any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(record), 0, 50, 1));
        when(recordStore.summarizeAccess(any()))
                .thenReturn(new AccessSummary(1, 1, OCCURRED, OCCURRED));
        when(recordStore.findChainHead()).thenReturn(Optional.of(record));
        when(redactionStore.findByRecordIds(List.of(1L))).thenReturn(Map.of(
                1L, List.of(new Redaction(1L, 1L, "accountNumber", RECORDED, "ops-admin", "GDPR"))));

        AuditRecordView view = service.report(ComplianceAccessFilter.empty(), 0, 50).events().getFirst();

        assertThat(view.visiblePayload().get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(view.record().payload().get("accountNumber").asText()).isEqualTo("1234");
        assertThat(view.redactedFields()).containsExactly("accountNumber");
    }

    @Test
    void rejectsAnInvertedOccurredRangeBeforeTouchingTheStore() {
        ComplianceAccessFilter filter = new ComplianceAccessFilter(
                null, null,
                Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T11:00:00Z"));

        assertThatThrownBy(() -> service.report(filter, 0, 10))
                .isInstanceOf(InvalidComplianceRequestException.class)
                .satisfies(thrown -> assertThat(((InvalidComplianceRequestException) thrown).code())
                        .isEqualTo("INVALID_QUERY"));

        verify(recordStore, never()).searchAccess(any(), anyInt(), anyInt());
    }

    @Test
    void clampsPageSizeToTheDocumentedMaximum() {
        when(recordStore.searchAccess(any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0, 200, 0));
        when(recordStore.summarizeAccess(any())).thenReturn(AccessSummary.empty());
        when(recordStore.findChainHead()).thenReturn(Optional.empty());

        service.report(ComplianceAccessFilter.empty(), 0, 5_000);

        verify(recordStore).searchAccess(any(), eq(0), eq(ComplianceReportService.MAX_PAGE_SIZE));
    }

    @Test
    void exportRefusesToMaterializeMoreRowsThanTheCap() {
        List<AuditRecord> tooMany = LongStream.rangeClosed(1, ComplianceReportService.MAX_EXPORT_RECORDS + 1)
                .mapToObj(i -> access(i, i, "ACCOUNT_VIEWED", "user-1", "acct-1", false))
                .toList();
        when(recordStore.findAccess(any(), anyInt())).thenReturn(tooMany);

        assertThatThrownBy(() -> service.export(ComplianceAccessFilter.empty()))
                .isInstanceOf(InvalidComplianceRequestException.class)
                .satisfies(thrown -> assertThat(((InvalidComplianceRequestException) thrown).code())
                        .isEqualTo("COMPLIANCE_EXPORT_TOO_LARGE"));
    }

    @Test
    void exportReturnsEveryMatchingRowOnASinglePage() {
        AuditRecord one = access(1L, 1L, "ACCOUNT_VIEWED", "user-1", "acct-1", false);
        AuditRecord two = access(2L, 3L, "ACCOUNT_UPDATED", "user-2", "acct-1", false);
        when(recordStore.findAccess(any(), anyInt())).thenReturn(List.of(one, two));
        when(recordStore.summarizeAccess(any()))
                .thenReturn(new AccessSummary(2, 2, OCCURRED, OCCURRED));
        when(recordStore.findChainHead()).thenReturn(Optional.of(two));

        ComplianceReport report = service.export(ComplianceAccessFilter.empty());

        assertThat(report.events()).hasSize(2);
        assertThat(report.page()).isZero();
        assertThat(report.totalElements()).isEqualTo(2);
    }

    private AuditRecord access(
            long id, long sequence, String eventType, String actorId, String resourceId, boolean redacted
    ) {
        return record(id, sequence, eventType, actorId, AccessScope.RESOURCE_TYPE, resourceId, redacted);
    }

    private AuditRecord record(
            long id, long sequence, String eventType, String actorId, String resourceType,
            String resourceId, boolean redacted
    ) {
        return new AuditRecord(
                id, sequence, eventType, actorId, resourceType, resourceId,
                canonicalJson.parse("{\"accountNumber\":\"1234\",\"ip\":\"10.0.0.1\"}"),
                OCCURRED, RECORDED, "c".repeat(64), sequence == 1 ? HashChainService.GENESIS_HASH : "p".repeat(64),
                RecordStatus.ACTIVE, null, redacted);
    }
}
