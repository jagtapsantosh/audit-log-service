package com.auditlog.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured redaction that keeps the chain verifiable.
 *
 * <p>The engineering problem is that {@code contentHash} covers the original payload, so masking a
 * value in place would invalidate that record and every link after it. The resolution here is that
 * redaction never writes to the payload: it appends an overlay row naming a field path, and reads
 * apply the mask. Verification continues to re-hash the stored original and therefore still passes,
 * while a genuine SQL edit of the payload is still caught.
 *
 * <p>The honest limitation: this protects API consumers, not anyone holding a SQL connection, who can
 * still read the original value. Field-level encryption is the production answer.
 */
@Service
public class RedactionService {

    private static final Logger log = LoggerFactory.getLogger(RedactionService.class);

    private static final int MAX_PATHS_PER_REQUEST = 50;

    private final AuditRecordStore recordStore;
    private final AuditRedactionStore redactionStore;
    private final RedactionOverlay overlay;
    private final Clock clock;
    private final Counter redactionsApplied;

    public RedactionService(
            AuditRecordStore recordStore,
            AuditRedactionStore redactionStore,
            RedactionOverlay overlay,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.recordStore = recordStore;
        this.redactionStore = redactionStore;
        this.overlay = overlay;
        this.clock = clock;
        this.redactionsApplied = Counter.builder("audit.redactions.applied")
                .description("Field paths redacted through the overlay")
                .register(meterRegistry);
    }

    /**
     * Redacts field paths on one record and returns the record as a reader now sees it.
     *
     * <p>Idempotent: redacting a path that is already redacted adds no second row and is not an
     * error, so a retried request cannot produce duplicate privacy history.
     */
    @Transactional
    public AuditRecordView redact(RedactionCommand command) {
        List<String> requested = validatePaths(command.fieldPaths());
        if (command.redactedBy() == null || command.redactedBy().isBlank()) {
            throw new InvalidRedactionException("REDACTED_BY_REQUIRED",
                    "the authenticated operator identity is required");
        }

        AuditRecord record = recordStore.findById(command.recordId())
                .orElseThrow(() -> new AuditRecordNotFoundException(command.recordId()));

        for (String path : requested) {
            if (!overlay.pathExists(record.payload(), path)) {
                throw new InvalidRedactionException("UNKNOWN_FIELD_PATH",
                        "payload has no field at path '" + path + "'");
            }
        }

        List<Redaction> existing = redactionStore.findByRecordId(record.id());
        Set<String> alreadyRedacted = new HashSet<>();
        existing.forEach(redaction -> alreadyRedacted.add(redaction.fieldPath()));

        Instant redactedAt = CanonicalJson.canonicalInstant(clock.instant());
        List<Redaction> toCreate = requested.stream()
                .filter(path -> !alreadyRedacted.contains(path))
                .map(path -> Redaction.pending(
                        record.id(), path, redactedAt, command.redactedBy(), command.reason()))
                .toList();

        List<Redaction> all = new ArrayList<>(existing);
        if (!toCreate.isEmpty()) {
            all.addAll(redactionStore.saveAll(toCreate));
            recordStore.markHasRedactions(record.id());
            redactionsApplied.increment(toCreate.size());
            log.info("Redacted {} field path(s) on record id={} sequence={} by={} reason={}",
                    toCreate.size(), record.id(), record.sequence(), command.redactedBy(),
                    command.reason());
        }
        return overlay.apply(record, all);
    }

    /** The record as a reader sees it, with any existing redactions applied. */
    @Transactional(readOnly = true)
    public AuditRecordView view(long recordId) {
        AuditRecord record = recordStore.findById(recordId)
                .orElseThrow(() -> new AuditRecordNotFoundException(recordId));
        return overlay.apply(record, redactionStore.findByRecordId(recordId));
    }

    private List<String> validatePaths(List<String> fieldPaths) {
        if (fieldPaths == null || fieldPaths.isEmpty()) {
            throw new InvalidRedactionException("FIELD_PATHS_REQUIRED",
                    "at least one field path is required");
        }
        if (fieldPaths.size() > MAX_PATHS_PER_REQUEST) {
            throw new InvalidRedactionException("TOO_MANY_FIELD_PATHS",
                    "at most " + MAX_PATHS_PER_REQUEST + " field paths per request");
        }
        for (String path : fieldPaths) {
            if (!overlay.isSyntacticallyValid(path)) {
                throw new InvalidRedactionException("INVALID_FIELD_PATH",
                        "'" + path + "' is not a dotted field path (array indexes are not supported)");
            }
        }
        return overlay.sortedDistinct(fieldPaths);
    }
}
