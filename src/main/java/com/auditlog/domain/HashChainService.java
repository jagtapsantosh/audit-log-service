package com.auditlog.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * SHA-256 hash chain over canonical JSON. Each record hashes its own content plus the hash of its
 * predecessor, so altering any past record invalidates that record and every link after it.
 */
@Service
public class HashChainService {

    /** Defined genesis value for the first record: 64 hex zeros. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private final CanonicalJson canonicalJson;

    public HashChainService(CanonicalJson canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    public String contentHash(ChainInput input) {
        return Sha256.hex(canonicalJson.serializeToBytes(canonicalForm(input)));
    }

    /** Exposed so tests and troubleshooting can show exactly what was hashed. */
    public String canonicalPreImage(ChainInput input) {
        return canonicalJson.serialize(canonicalForm(input));
    }

    private ObjectNode canonicalForm(ChainInput input) {
        ObjectNode node = canonicalJson.newObject();
        node.put("actorId", input.actorId());
        node.put("eventType", input.eventType());
        node.put("occurredAt", CanonicalJson.canonicalInstant(input.occurredAt()).toString());
        node.set("payload", input.payload() == null ? canonicalJson.emptyObject() : input.payload());
        node.put("previousHash", input.previousHash());
        node.put("recordedAt", CanonicalJson.canonicalInstant(input.recordedAt()).toString());
        node.put("resourceId", input.resourceId());
        node.put("resourceType", input.resourceType());
        node.put("sequence", input.sequence());
        return node;
    }
}
