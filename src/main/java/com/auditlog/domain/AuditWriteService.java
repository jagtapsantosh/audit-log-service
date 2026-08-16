package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends one link to the chain. This is the only write path: there is no update or delete anywhere
 * in the service.
 */
@Service
public class AuditWriteService {

    /** Payload cap; large blobs belong in the system of record, not in an audit trail. */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    /**
     * A caller may backdate {@code occurredAt} arbitrarily (offline or batched producers), but an
     * event cannot claim to have happened meaningfully in the future.
     */
    public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(AuditWriteService.class);

    private final AuditRecordStore store;
    private final IdempotencyStore idempotencyStore;
    private final HashChainService hashChainService;
    private final CanonicalJson canonicalJson;
    private final Clock clock;
    private final Counter eventsWritten;

    public AuditWriteService(
            AuditRecordStore store,
            IdempotencyStore idempotencyStore,
            HashChainService hashChainService,
            CanonicalJson canonicalJson,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.store = store;
        this.idempotencyStore = idempotencyStore;
        this.hashChainService = hashChainService;
        this.canonicalJson = canonicalJson;
        this.clock = clock;
        this.eventsWritten = Counter.builder("audit.events.written")
                .description("Audit events appended to the chain")
                .register(meterRegistry);
    }

    @Transactional
    public AuditRecord append(NewAuditEvent event) {
        return append(event, null).record();
    }

    @Transactional
    public AppendResult append(NewAuditEvent event, IdempotencyKey idempotencyKey) {
        Instant recordedAt = CanonicalJson.canonicalInstant(clock.instant());
        Instant occurredAt = CanonicalJson.canonicalInstant(event.occurredAt());
        if (occurredAt.isAfter(recordedAt.plus(MAX_FUTURE_SKEW))) {
            throw new InvalidAuditEventException(
                    "OCCURRED_AT_IN_FUTURE",
                    "occurredAt must not be more than " + MAX_FUTURE_SKEW.toMinutes()
                            + " minutes after the server clock");
        }

        JsonNode payload = normalizePayload(event.payload());
        String requestHash = idempotencyKey == null ? null : requestHash(event, payload);

        // The lock is taken before reading the head so concurrent appends cannot observe the same
        // predecessor and produce two records with the same previousHash. Replay lookup sits inside
        // the same lock so two retries cannot both miss and append twice.
        store.lockChain();
        if (idempotencyKey != null) {
            Optional<AppendResult> replay = replayIfPresent(idempotencyKey, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        Optional<AuditRecord> head = store.findChainHead();
        long sequence = head.map(AuditRecord::sequence).orElse(0L) + 1;
        String previousHash = head.map(AuditRecord::contentHash).orElse(HashChainService.GENESIS_HASH);

        ChainInput chainInput = new ChainInput(
                sequence,
                event.eventType(),
                event.actorId(),
                event.resourceType(),
                event.resourceId(),
                payload,
                occurredAt,
                recordedAt,
                previousHash);

        AuditRecord persisted = store.append(new AuditRecord(
                null,
                sequence,
                event.eventType(),
                event.actorId(),
                event.resourceType(),
                event.resourceId(),
                payload,
                occurredAt,
                recordedAt,
                hashChainService.contentHash(chainInput),
                previousHash));

        store.publishHead(new ChainHead(persisted.sequence(), persisted.contentHash()), recordedAt);
        if (idempotencyKey != null && persisted.id() != null) {
            idempotencyStore.save(new IdempotencyRecord(
                    idempotencyKey.clientId(),
                    idempotencyKey.key(),
                    requestHash,
                    persisted.id(),
                    recordedAt));
        }
        eventsWritten.increment();
        log.info(
                "Appended audit record sequence={} eventType={} resource={}/{}",
                persisted.sequence(),
                persisted.eventType(),
                persisted.resourceType(),
                persisted.resourceId());
        return AppendResult.created(persisted);
    }

    private Optional<AppendResult> replayIfPresent(IdempotencyKey idempotencyKey, String requestHash) {
        Optional<IdempotencyRecord> existing = idempotencyStore.find(idempotencyKey.clientId(), idempotencyKey.key());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (!existing.get().requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        AuditRecord original = store.findById(existing.get().auditRecordId())
                .orElseThrow(() -> new IllegalStateException("Idempotency key points at a missing record"));
        return Optional.of(AppendResult.replayed(original));
    }

    private String requestHash(NewAuditEvent event, JsonNode payload) {
        var node = canonicalJson.newObject();
        node.put("actorId", event.actorId());
        node.put("eventType", event.eventType());
        node.put("occurredAt", CanonicalJson.canonicalInstant(event.occurredAt()).toString());
        node.set("payload", payload);
        node.put("resourceId", event.resourceId());
        node.put("resourceType", event.resourceType());
        return Sha256.hex(canonicalJson.serializeToBytes(node));
    }

    private JsonNode normalizePayload(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return canonicalJson.emptyObject();
        }
        if (!payload.isObject()) {
            throw new InvalidAuditEventException("PAYLOAD_NOT_OBJECT", "payload must be a JSON object");
        }
        int bytes = canonicalJson.serialize(payload).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAYLOAD_BYTES) {
            throw new InvalidAuditEventException(
                    "PAYLOAD_TOO_LARGE", "payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return payload;
    }
}
