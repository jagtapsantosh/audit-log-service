package com.auditlog.domain;

import com.auditlog.config.RetentionProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention by soft archive.
 *
 * <p>Records past the window are marked ARCHIVED and nothing else: the payload, both clocks, and both
 * hashes are left exactly as written, and the row stays in place. That is deliberate — physically
 * removing a record would either leave a sequence gap or orphan its successor's
 * {@code previousHash}, which verification would correctly report as a break. So the price of honest
 * verification is that storage grows; tiering or partitioning is the production answer.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final AuditRecordStore store;
    private final RetentionProperties properties;
    private final Clock clock;
    private final Counter recordsArchived;

    public RetentionService(
            AuditRecordStore store,
            RetentionProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.recordsArchived = Counter.builder("audit.records.archived")
                .description("Audit records soft-archived by retention policy")
                .register(meterRegistry);
    }

    /**
     * Archives every ACTIVE record ingested before the cutoff. Idempotent: running it twice archives
     * nothing new, because the statement only matches rows that are still ACTIVE.
     */
    @Transactional
    public ArchiveResult sweep() {
        Instant now = CanonicalJson.canonicalInstant(clock.instant());
        Instant cutoff = now.minus(Duration.ofDays(properties.days()));
        int archived = store.archiveRecordedBefore(cutoff, now);
        recordsArchived.increment(archived);
        log.info("Retention sweep archived {} record(s) recorded before {} (window {} days)",
                archived, cutoff, properties.days());
        return new ArchiveResult(archived, cutoff, properties.days());
    }

    /** Outcome of one sweep, reported back to the operator who triggered it. */
    public record ArchiveResult(int archived, Instant cutoff, int retentionDays) {}
}
