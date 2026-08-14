package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.auditlog.domain.AuditQueryService.InvalidAuditQueryException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-14T11:30:00Z");
    private static final Instant RECORDED = Instant.parse("2026-08-14T11:37:00Z");

    @Mock
    private AuditRecordStore store;

    @Mock
    private AuditRedactionStore redactionStore;

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final RedactionOverlay overlay = new RedactionOverlay();

    private AuditQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new AuditQueryService(store, redactionStore, overlay);
    }

    @Test
    void appliesDefaultPagingWhenUnspecified() {
        stubSearch(List.of());

        queryService.search(AuditQueryFilter.empty(), null, null);

        verify(store).search(any(), eq(0), eq(AuditQueryService.DEFAULT_PAGE_SIZE));
    }

    @Test
    void clampsPageSizeToTheDocumentedMaximum() {
        stubSearch(List.of());

        queryService.search(AuditQueryFilter.empty(), 2, 5_000);

        verify(store).search(any(), eq(2), eq(AuditQueryService.MAX_PAGE_SIZE));
    }

    @Test
    void rejectsInvertedOccurredRange() {
        AuditQueryFilter filter = new AuditQueryFilter(
                null, null, null, null,
                Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T11:00:00Z"),
                null, null);

        assertThatThrownBy(() -> queryService.search(filter, 0, 10))
                .isInstanceOf(InvalidAuditQueryException.class);

        verify(store, never()).search(any(), anyInt(), anyInt());
    }

    @Test
    void rejectsInvertedRecordedRange() {
        AuditQueryFilter filter = new AuditQueryFilter(
                null, null, null, null, null, null,
                Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T11:00:00Z"));

        assertThatThrownBy(() -> queryService.search(filter, 0, 10))
                .isInstanceOf(InvalidAuditQueryException.class);
    }

    @Test
    void passesFiltersThroughUnchanged() {
        stubSearch(List.of());
        AuditQueryFilter filter = new AuditQueryFilter(
                "user-1", "SESSION", "sess-1", "USER_LOGIN", null, null, null, null);

        queryService.search(filter, 0, 10);

        verify(store).search(eq(filter), eq(0), eq(10));
    }

    @Test
    void archivedRecordsAreExcludedUnlessAskedFor() {
        assertThat(AuditQueryFilter.empty().statuses()).containsExactly(RecordStatus.ACTIVE);
        assertThat(new AuditQueryFilter(null, null, null, null, null, null, null, null, true).statuses())
                .containsExactly(RecordStatus.ACTIVE, RecordStatus.ARCHIVED);
    }

    @Test
    void masksRedactedPathsOnTheWayOut() {
        AuditRecord record = record(1L, "{\"accountNumber\":\"123\",\"ip\":\"10.0.0.1\"}", true);
        stubSearch(List.of(record));
        when(redactionStore.findByRecordIds(List.of(1L))).thenReturn(Map.of(
                1L, List.of(new Redaction(9L, 1L, "accountNumber", RECORDED, "ops-admin", "GDPR"))));

        AuditRecordView view = queryService.search(AuditQueryFilter.empty(), 0, 10).content().getFirst();

        assertThat(view.visiblePayload().get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(view.visiblePayload().get("ip").asText()).isEqualTo("10.0.0.1");
        assertThat(view.redactedFields()).containsExactly("accountNumber");
        // The stored payload the chain hashes is untouched by the read.
        assertThat(view.record().payload().get("accountNumber").asText()).isEqualTo("123");
    }

    @Test
    void doesNotQueryRedactionsForRecordsThatHaveNone() {
        stubSearch(List.of(record(1L, "{\"ip\":\"10.0.0.1\"}", false)));

        AuditRecordView view = queryService.search(AuditQueryFilter.empty(), 0, 10).content().getFirst();

        assertThat(view.redactedFields()).isEmpty();
        verifyNoInteractions(redactionStore);
    }

    private void stubSearch(List<AuditRecord> content) {
        when(store.search(any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(content, 0, 50, content.size()));
    }

    private AuditRecord record(long id, String payloadJson, boolean hasRedactions) {
        JsonNode payload = canonicalJson.parse(payloadJson);
        return new AuditRecord(id, id, "USER_LOGIN", "user-1", "SESSION", "sess-1", payload,
                OCCURRED, RECORDED, "a".repeat(64), "0".repeat(64),
                RecordStatus.ACTIVE, null, hasRedactions);
    }
}
