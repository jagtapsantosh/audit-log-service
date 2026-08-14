package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HashChainServiceTest {

    /**
     * Golden vector. The expected hash was produced outside this codebase
     * ({@code printf '%s' '<pre-image>' | shasum -a 256}), so a refactor of the serializer cannot
     * silently redefine what "correct" means.
     */
    private static final String GOLDEN_PRE_IMAGE = "{\"actorId\":\"user-123\","
            + "\"eventType\":\"USER_LOGIN\","
            + "\"occurredAt\":\"2026-08-14T11:30:00Z\","
            + "\"payload\":{\"ip\":\"10.0.0.1\"},"
            + "\"previousHash\":\"0000000000000000000000000000000000000000000000000000000000000000\","
            + "\"recordedAt\":\"2026-08-14T11:37:00Z\","
            + "\"resourceId\":\"sess-abc\","
            + "\"resourceType\":\"SESSION\","
            + "\"sequence\":1}";

    private static final String GOLDEN_HASH =
            "92a17e017b990a95451da1bf7d6836ca05197ebb5acb83060334bef4cec9f2e4";

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final HashChainService hashChainService = new HashChainService(canonicalJson);

    @Test
    void genesisHashIsSixtyFourHexZeros() {
        assertThat(HashChainService.GENESIS_HASH).hasSize(64).matches("0{64}");
    }

    @Test
    @DisplayName("known record hashes to the known hex, over the documented pre-image")
    void producesGoldenVector() {
        ChainInput input = goldenInput();

        assertThat(hashChainService.canonicalPreImage(input)).isEqualTo(GOLDEN_PRE_IMAGE);
        assertThat(hashChainService.contentHash(input)).isEqualTo(GOLDEN_HASH);
    }

    @Test
    void hashingIsDeterministicAcrossPayloadKeyOrder() {
        ChainInput reordered = withPayload(goldenInput(), "{\"ip\":\"10.0.0.1\",\"extra\":1}");
        ChainInput sameFieldsOtherOrder = withPayload(goldenInput(), "{\"extra\":1,\"ip\":\"10.0.0.1\"}");

        assertThat(hashChainService.contentHash(reordered))
                .isEqualTo(hashChainService.contentHash(sameFieldsOtherOrder));
    }

    @Test
    void missingPayloadHashesAsEmptyObject() {
        ChainInput withoutPayload = withPayload(goldenInput(), null);
        ChainInput withEmptyPayload = withPayload(goldenInput(), "{}");

        assertThat(hashChainService.contentHash(withoutPayload))
                .isEqualTo(hashChainService.contentHash(withEmptyPayload));
    }

    @Test
    @DisplayName("every hashed field changes the hash, including both clocks")
    void everyHashedFieldAffectsTheHash() {
        ChainInput base = goldenInput();
        String baseHash = hashChainService.contentHash(base);

        assertThat(hashChainService.contentHash(withPayload(base, "{\"ip\":\"10.0.0.2\"}")))
                .isNotEqualTo(baseHash);
        assertThat(hashChainService.contentHash(copy(base).sequence(2).build())).isNotEqualTo(baseHash);
        assertThat(hashChainService.contentHash(copy(base).eventType("USER_LOGOUT").build()))
                .isNotEqualTo(baseHash);
        assertThat(hashChainService.contentHash(copy(base).actorId("user-124").build()))
                .isNotEqualTo(baseHash);
        assertThat(hashChainService.contentHash(copy(base).resourceType("ACCOUNT").build()))
                .isNotEqualTo(baseHash);
        assertThat(hashChainService.contentHash(copy(base).resourceId("sess-abd").build()))
                .isNotEqualTo(baseHash);
        assertThat(hashChainService.contentHash(
                        copy(base).occurredAt(Instant.parse("2026-08-14T11:30:01Z")).build()))
                .isNotEqualTo(baseHash);
        assertThat(hashChainService.contentHash(
                        copy(base).recordedAt(Instant.parse("2026-08-14T11:37:01Z")).build()))
                .isNotEqualTo(baseHash);
        assertThat(hashChainService.contentHash(copy(base).previousHash("1".repeat(64)).build()))
                .isNotEqualTo(baseHash);
    }

    @Test
    @DisplayName("retention and redaction metadata are not hashed, so archive/redact cannot break verify")
    void statusAndRedactionMetadataAreOutsideThePreImage() {
        assertThat(hashChainService.canonicalPreImage(goldenInput()))
                .doesNotContain("status")
                .doesNotContain("archived")
                .doesNotContain("redact")
                .doesNotContain("ARCHIVED");
    }

    @Test
    @DisplayName("nanosecond precision is truncated, so a database round trip re-hashes identically")
    void ignoresSubMicrosecondPrecision() {
        ChainInput nanos = copy(goldenInput())
                .occurredAt(Instant.parse("2026-08-14T11:30:00.123456789Z"))
                .build();
        ChainInput truncated = copy(goldenInput())
                .occurredAt(Instant.parse("2026-08-14T11:30:00.123456Z"))
                .build();

        assertThat(hashChainService.contentHash(nanos)).isEqualTo(hashChainService.contentHash(truncated));
    }

    private ChainInput goldenInput() {
        return new ChainInput(
                1,
                "USER_LOGIN",
                "user-123",
                "SESSION",
                "sess-abc",
                canonicalJson.parse("{\"ip\":\"10.0.0.1\"}"),
                Instant.parse("2026-08-14T11:30:00Z"),
                Instant.parse("2026-08-14T11:37:00Z"),
                HashChainService.GENESIS_HASH);
    }

    private ChainInput withPayload(ChainInput input, String payloadJson) {
        JsonNode payload = payloadJson == null ? null : canonicalJson.parse(payloadJson);
        return copy(input).payload(payload).build();
    }

    private static Builder copy(ChainInput input) {
        return new Builder(input);
    }

    /** Small mutable builder so each assertion can vary exactly one hashed field. */
    private static final class Builder {
        private long sequence;
        private String eventType;
        private String actorId;
        private String resourceType;
        private String resourceId;
        private JsonNode payload;
        private Instant occurredAt;
        private Instant recordedAt;
        private String previousHash;

        private Builder(ChainInput input) {
            this.sequence = input.sequence();
            this.eventType = input.eventType();
            this.actorId = input.actorId();
            this.resourceType = input.resourceType();
            this.resourceId = input.resourceId();
            this.payload = input.payload();
            this.occurredAt = input.occurredAt();
            this.recordedAt = input.recordedAt();
            this.previousHash = input.previousHash();
        }

        Builder sequence(long value) {
            this.sequence = value;
            return this;
        }

        Builder eventType(String value) {
            this.eventType = value;
            return this;
        }

        Builder actorId(String value) {
            this.actorId = value;
            return this;
        }

        Builder resourceType(String value) {
            this.resourceType = value;
            return this;
        }

        Builder resourceId(String value) {
            this.resourceId = value;
            return this;
        }

        Builder payload(JsonNode value) {
            this.payload = value;
            return this;
        }

        Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        Builder recordedAt(Instant value) {
            this.recordedAt = value;
            return this;
        }

        Builder previousHash(String value) {
            this.previousHash = value;
            return this;
        }

        ChainInput build() {
            return new ChainInput(
                    sequence,
                    eventType,
                    actorId,
                    resourceType,
                    resourceId,
                    payload,
                    occurredAt,
                    recordedAt,
                    previousHash);
        }
    }
}
