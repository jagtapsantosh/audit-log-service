package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.auditlog.config.RetentionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00.123456789Z");

    @Mock
    private AuditRecordStore store;

    @Test
    void archivesRecordsIngestedBeforeTheWindow() {
        when(store.archiveRecordedBefore(any(), any())).thenReturn(12);

        RetentionService.ArchiveResult result = service(365).sweep();

        assertThat(result.archived()).isEqualTo(12);
        assertThat(result.retentionDays()).isEqualTo(365);
        assertThat(result.cutoff()).isEqualTo(Instant.parse("2025-08-14T12:00:00.123456Z"));
        verify(store).archiveRecordedBefore(
                Instant.parse("2025-08-14T12:00:00.123456Z"),
                Instant.parse("2026-08-14T12:00:00.123456Z"));
    }

    @Test
    void theCutoffIsTheIngestClockNotTheEventClock() {
        // Nothing in this service reads occurredAt: a producer cannot keep a row hot by backdating it.
        when(store.archiveRecordedBefore(any(), any())).thenReturn(0);

        service(30).sweep();

        verify(store).archiveRecordedBefore(
                Instant.parse("2026-07-15T12:00:00.123456Z"),
                Instant.parse("2026-08-14T12:00:00.123456Z"));
    }

    @Test
    void aZeroDayWindowArchivesEverythingAlreadyIngested() {
        when(store.archiveRecordedBefore(any(), any())).thenReturn(3);

        RetentionService.ArchiveResult result = service(0).sweep();

        assertThat(result.cutoff()).isEqualTo(Instant.parse("2026-08-14T12:00:00.123456Z"));
        assertThat(result.archived()).isEqualTo(3);
    }

    @Test
    void aSweepWithNothingToDoReportsZero() {
        when(store.archiveRecordedBefore(any(), any())).thenReturn(0);

        assertThat(service(365).sweep().archived()).isZero();
    }

    @Test
    void neverAsksTheStoreToDeleteAnything() {
        when(store.archiveRecordedBefore(any(), any())).thenReturn(5);

        service(365).sweep();

        // AuditRecordStore has no delete method at all; this asserts the sweep's only write.
        verify(store).archiveRecordedBefore(any(), any());
        verifyNoMoreInteractions(store);
    }

    private RetentionService service(int days) {
        return new RetentionService(
                store,
                new RetentionProperties(days, new RetentionProperties.Sweep(true, "0 30 3 * * *")),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }
}
