package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auditlog.domain.AuditQueryService.InvalidAuditQueryException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock
    private AuditRecordStore store;

    @InjectMocks
    private AuditQueryService queryService;

    @Test
    void appliesDefaultPagingWhenUnspecified() {
        stubSearch();

        queryService.search(AuditQueryFilter.empty(), null, null);

        verify(store).search(any(), eq(0), eq(AuditQueryService.DEFAULT_PAGE_SIZE));
    }

    @Test
    void clampsPageSizeToTheDocumentedMaximum() {
        stubSearch();

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
        stubSearch();
        AuditQueryFilter filter = new AuditQueryFilter(
                "user-1", "SESSION", "sess-1", "USER_LOGIN", null, null, null, null);

        queryService.search(filter, 0, 10);

        verify(store).search(eq(filter), eq(0), eq(10));
    }

    private void stubSearch() {
        when(store.search(any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0, 50, 0));
    }
}
