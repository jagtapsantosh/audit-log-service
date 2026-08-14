package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Recipient-side checks. These are written from the recipient's point of view: parse the file, decide
 * whether to trust it, without any access to the service or its database.
 */
class ExportVerifierTest {

    private static final Instant EXPORTED_AT = Instant.parse("2026-08-16T12:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-08-14T11:30:00Z");
    private static final Instant RECORDED = Instant.parse("2026-08-14T11:37:00Z");

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final HashChainService hashChainService = new HashChainService(canonicalJson);
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final ExportVerifier verifier = new ExportVerifier();

    @Test
    void acceptsAnUntouchedBundle() {
        JsonNode bundle = bundleJson(unredactedRecord(1L), unredactedRecord(9L));

        ExportVerifier.Report report = verifier.verify(bundle);

        assertThat(report.intact()).isTrue();
        assertThat(report.bundleHashValid()).isTrue();
        assertThat(report.recordsInBundle()).isEqualTo(2);
        assertThat(report.recordsRehashed()).isEqualTo(2);
        assertThat(report.recordsSkippedBecauseRedacted()).isZero();
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void acceptsGapsInSequenceNumbers() {
        // A filtered export is a sparse slice of the global chain, so 1 then 9 is normal.
        JsonNode bundle = bundleJson(unredactedRecord(1L), unredactedRecord(9L));

        assertThat(verifier.verify(bundle).findings()).isEmpty();
    }

    @Test
    void acceptsAnEmptyBundle() {
        assertThat(verifier.verify(bundleJson()).intact()).isTrue();
    }

    @Test
    void detectsAnEditedPayload() {
        ObjectNode bundle = (ObjectNode) bundleJson(unredactedRecord(1L));
        ((ObjectNode) bundle.get("records").get(0).get("payload")).put("ip", "10.9.9.9");

        ExportVerifier.Report report = verifier.verify(bundle);

        assertThat(report.intact()).isFalse();
        assertThat(report.bundleHashValid()).isFalse();
        assertThat(report.findings()).anyMatch(finding -> finding.startsWith("bundleHash mismatch"));
    }

    @Test
    void detectsAnEditedExportTimestamp() {
        ObjectNode bundle = (ObjectNode) bundleJson(unredactedRecord(1L));
        bundle.put("exportedAt", "2020-01-01T00:00:00Z");

        assertThat(verifier.verify(bundle).bundleHashValid()).isFalse();
    }

    @Test
    void detectsARemovedRecord() {
        ObjectNode bundle = (ObjectNode) bundleJson(unredactedRecord(1L), unredactedRecord(9L));
        ((ArrayNode) bundle.get("records")).remove(1);

        assertThat(verifier.verify(bundle).bundleHashValid()).isFalse();
    }

    @Test
    void detectsARestatedFilter() {
        ObjectNode bundle = (ObjectNode) bundleJson(unredactedRecord(1L));
        ((ObjectNode) bundle.get("filter")).put("actorId", "someone-else");

        assertThat(verifier.verify(bundle).bundleHashValid()).isFalse();
    }

    @Test
    void detectsAMissingBundleHash() {
        ObjectNode bundle = (ObjectNode) bundleJson(unredactedRecord(1L));
        bundle.remove("bundleHash");

        ExportVerifier.Report report = verifier.verify(bundle);

        assertThat(report.intact()).isFalse();
        assertThat(report.findings()).contains("bundleHash is missing");
    }

    @Test
    void detectsAnEditedPayloadEvenWhenTheBundleHashIsResealed() {
        // The stronger claim: someone who knows the algorithm can re-seal the file, but they cannot
        // make an unredacted record hash back to the server's contentHash.
        ObjectNode bundle = (ObjectNode) bundleJson(unredactedRecord(1L));
        ((ObjectNode) bundle.get("records").get(0).get("payload")).put("ip", "10.9.9.9");
        bundle.put("bundleHash", ExportBundleService.hashOf(bundle, canonicalJson));

        ExportVerifier.Report report = verifier.verify(bundle);

        assertThat(report.bundleHashValid()).isTrue();
        assertThat(report.intact()).isFalse();
        assertThat(report.findings()).anyMatch(finding -> finding.startsWith("contentHash mismatch at sequence 1"));
    }

    @Test
    void detectsAnEditedClockOnAnUnredactedRecord() {
        ObjectNode bundle = (ObjectNode) bundleJson(unredactedRecord(1L));
        ((ObjectNode) bundle.get("records").get(0)).put("occurredAt", "2020-01-01T00:00:00Z");
        bundle.put("bundleHash", ExportBundleService.hashOf(bundle, canonicalJson));

        assertThat(verifier.verify(bundle).findings())
                .anyMatch(finding -> finding.startsWith("contentHash mismatch"));
    }

    @Test
    void skipsContentRehashForRedactedRecords() {
        JsonNode bundle = bundleJson(redactedRecord(1L));

        ExportVerifier.Report report = verifier.verify(bundle);

        assertThat(report.intact()).isTrue();
        assertThat(report.recordsRehashed()).isZero();
        assertThat(report.recordsSkippedBecauseRedacted()).isEqualTo(1);
    }

    @Test
    void aRedactedRecordThatIsResealedCannotBeRehashed() {
        // Owned limitation: masked payloads carry no recomputable hash, so bundleHash is the only
        // guarantee for them. Full integrity for redacted records stays with GET /audit/verify.
        ObjectNode bundle = (ObjectNode) bundleJson(redactedRecord(1L));
        ((ObjectNode) bundle.get("records").get(0).get("payload")).put("ip", "10.9.9.9");
        bundle.put("bundleHash", ExportBundleService.hashOf(bundle, canonicalJson));

        assertThat(verifier.verify(bundle).intact()).isTrue();
    }

    @Test
    void rejectsSomethingThatIsNotABundle() throws IOException {
        assertThat(verifier.verify("[]").intact()).isFalse();
        assertThat(verifier.verify("{}").findings())
                .contains("bundleHash is missing", "bundle has no records array");
    }

    @Test
    void reportsARecordThatCannotBeRehashed() {
        ObjectNode bundle = (ObjectNode) bundleJson(unredactedRecord(1L));
        ((ObjectNode) bundle.get("records").get(0)).remove("recordedAt");
        bundle.put("bundleHash", ExportBundleService.hashOf(bundle, canonicalJson));

        assertThat(verifier.verify(bundle).findings())
                .anyMatch(finding -> finding.contains("missing fields required to re-hash"));
    }

    @Test
    void verifiesFromRawJsonText() throws IOException {
        String json = mapper.writeValueAsString(bundleJson(unredactedRecord(1L)));

        assertThat(verifier.verify(json).intact()).isTrue();
    }

    /** Builds a bundle exactly as the service would, then seals it. */
    private JsonNode bundleJson(ExportRecord... records) {
        ExportBundle unsealed = new ExportBundle(ExportBundle.CURRENT_VERSION, EXPORTED_AT,
                ExportFilter.forActor("user-123"), HashChainService.GENESIS_HASH, List.of(records), null);
        ObjectNode node = mapper.valueToTree(unsealed);
        node.put("bundleHash", ExportBundleService.hashOf(node, canonicalJson));
        return node;
    }

    private ExportRecord unredactedRecord(long sequence) {
        JsonNode payload = canonicalJson.parse("{\"ip\":\"10.0.0.1\"}");
        return new ExportRecord(sequence, "ACCOUNT_VIEWED", "user-123", "CLIENT_ACCOUNT", "acct-1",
                OCCURRED, RECORDED, contentHash(sequence, payload), previousHash(sequence), payload,
                List.of());
    }

    /** A redacted record carries the server hash of the original payload, not of the masked one. */
    private ExportRecord redactedRecord(long sequence) {
        JsonNode original = canonicalJson.parse("{\"accountNumber\":\"1234\",\"ip\":\"10.0.0.1\"}");
        JsonNode masked = new RedactionOverlay().mask(original, List.of("accountNumber"));
        return new ExportRecord(sequence, "ACCOUNT_VIEWED", "user-123", "CLIENT_ACCOUNT", "acct-1",
                OCCURRED, RECORDED, contentHash(sequence, original), previousHash(sequence), masked,
                List.of("accountNumber"));
    }

    private String contentHash(long sequence, JsonNode payload) {
        return hashChainService.contentHash(new ChainInput(sequence, "ACCOUNT_VIEWED", "user-123",
                "CLIENT_ACCOUNT", "acct-1", payload, OCCURRED, RECORDED, previousHash(sequence)));
    }

    private static String previousHash(long sequence) {
        return sequence == 1 ? HashChainService.GENESIS_HASH : "b".repeat(64);
    }
}
