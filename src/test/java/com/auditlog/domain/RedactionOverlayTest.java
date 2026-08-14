package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The overlay is the whole reason redaction does not break the chain, so its edge cases are covered
 * here rather than only through the API.
 */
class RedactionOverlayTest {

    private static final Instant WHEN = Instant.parse("2026-08-14T11:37:00Z");

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final RedactionOverlay overlay = new RedactionOverlay();

    @Test
    void masksATopLevelField() {
        JsonNode payload = canonicalJson.parse("{\"accountNumber\":\"123456\",\"ip\":\"10.0.0.1\"}");

        JsonNode masked = overlay.mask(payload, List.of("accountNumber"));

        assertThat(masked.get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(masked.get("ip").asText()).isEqualTo("10.0.0.1");
    }

    @Test
    void masksANestedField() {
        JsonNode payload = canonicalJson.parse("{\"customer\":{\"ssn\":\"111-22-3333\",\"tier\":\"gold\"}}");

        JsonNode masked = overlay.mask(payload, List.of("customer.ssn"));

        assertThat(masked.get("customer").get("ssn").asText()).isEqualTo("[REDACTED]");
        assertThat(masked.get("customer").get("tier").asText()).isEqualTo("gold");
    }

    @Test
    void maskingNeverMutatesTheStoredPayload() {
        JsonNode payload = canonicalJson.parse("{\"accountNumber\":\"123456\"}");

        overlay.mask(payload, List.of("accountNumber"));

        // If this ever fails, verification would start reporting honest records as tampered.
        assertThat(payload.get("accountNumber").asText()).isEqualTo("123456");
    }

    @Test
    void masksAnObjectValueWholesale() {
        JsonNode payload = canonicalJson.parse("{\"customer\":{\"ssn\":\"111\",\"name\":\"A\"},\"ip\":\"1\"}");

        JsonNode masked = overlay.mask(payload, List.of("customer"));

        assertThat(masked.get("customer").asText()).isEqualTo("[REDACTED]");
        assertThat(masked.get("ip").asText()).isEqualTo("1");
    }

    @Test
    void masksNumericAndBooleanValuesAsText() {
        JsonNode payload = canonicalJson.parse("{\"amount\":100.50,\"vip\":true}");

        JsonNode masked = overlay.mask(payload, List.of("amount", "vip"));

        assertThat(masked.get("amount").asText()).isEqualTo("[REDACTED]");
        assertThat(masked.get("vip").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void unknownPathsAreLeftAloneRatherThanCreated() {
        JsonNode payload = canonicalJson.parse("{\"ip\":\"10.0.0.1\"}");

        JsonNode masked = overlay.mask(payload, List.of("accountNumber", "customer.ssn"));

        assertThat(masked.has("accountNumber")).isFalse();
        assertThat(masked.has("customer")).isFalse();
        assertThat(masked.get("ip").asText()).isEqualTo("10.0.0.1");
    }

    @Test
    void reportsWhetherAPathExists() {
        JsonNode payload = canonicalJson.parse("""
                {"accountNumber":"1","customer":{"ssn":"2"},"tags":["a"],"nullable":null}""");

        assertThat(overlay.pathExists(payload, "accountNumber")).isTrue();
        assertThat(overlay.pathExists(payload, "customer")).isTrue();
        assertThat(overlay.pathExists(payload, "customer.ssn")).isTrue();
        assertThat(overlay.pathExists(payload, "nullable")).isTrue();
        assertThat(overlay.pathExists(payload, "missing")).isFalse();
        assertThat(overlay.pathExists(payload, "customer.missing")).isFalse();
        // accountNumber is a scalar, so it has no children.
        assertThat(overlay.pathExists(payload, "accountNumber.deeper")).isFalse();
        // Arrays are out of scope for v1 rather than half-supported.
        assertThat(overlay.pathExists(payload, "tags.0")).isFalse();
    }

    @Test
    void anEmptyPayloadHasNoPaths() {
        assertThat(overlay.pathExists(canonicalJson.parse("{}"), "anything")).isFalse();
        assertThat(overlay.pathExists(null, "anything")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", ".leading", "trailing.", "a..b", "items[0]", "items[0].id"})
    void rejectsMalformedPaths(String path) {
        assertThat(overlay.isSyntacticallyValid(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"accountNumber", "customer.ssn", "a.b.c.d", "with-dash", "with_underscore"})
    void acceptsDottedPaths(String path) {
        assertThat(overlay.isSyntacticallyValid(path)).isTrue();
    }

    @Test
    void rejectsPathsLongerThanTheColumn() {
        assertThat(overlay.isSyntacticallyValid("a".repeat(255))).isTrue();
        assertThat(overlay.isSyntacticallyValid("a".repeat(256))).isFalse();
    }

    @Test
    void appliedViewListsRedactedFieldsSortedAndDeduplicated() {
        AuditRecord record = record("{\"b\":\"1\",\"a\":\"2\"}");

        AuditRecordView view = overlay.apply(record, List.of(
                redaction(record.id(), "b"), redaction(record.id(), "a"), redaction(record.id(), "b")));

        assertThat(view.redactedFields()).containsExactly("a", "b");
        assertThat(view.visiblePayload().get("a").asText()).isEqualTo("[REDACTED]");
        assertThat(view.visiblePayload().get("b").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void aRecordWithNoRedactionsIsReturnedAsIs() {
        AuditRecord record = record("{\"ip\":\"10.0.0.1\"}");

        AuditRecordView view = overlay.apply(record, List.of());

        assertThat(view.redactedFields()).isEmpty();
        assertThat(view.visiblePayload()).isSameAs(record.payload());
    }

    private Redaction redaction(long recordId, String path) {
        return new Redaction(null, recordId, path, WHEN, "ops-admin", "GDPR");
    }

    private AuditRecord record(String payloadJson) {
        return new AuditRecord(1L, 1L, "USER_LOGIN", "user-1", "SESSION", "sess-1",
                canonicalJson.parse(payloadJson), WHEN, WHEN, "a".repeat(64), "0".repeat(64));
    }
}
