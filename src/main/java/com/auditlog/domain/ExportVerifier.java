package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Recipient-side check for an export bundle. Deliberately standalone: no Spring, no database, no
 * network — construct it and hand it the file, so whoever received the bundle can verify it without
 * running this service.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li>Recompute {@code bundleHash} over the canonical form of the document with {@code bundleHash}
 *       removed. A mismatch means the file changed after export.
 *   <li>For each record whose {@code redactedFields} is empty, recompute {@code contentHash} from the
 *       fields in the file and compare it to the server's copied value.
 *   <li>Skip that re-hash for redacted records: their payload in the file is masked, while the server
 *       hash covers the original value. This is a stated limitation, not an oversight.
 *   <li>Never fail on gaps in {@code sequence}. A filtered export is a sparse slice of the global
 *       chain, so gaps are the normal case.
 * </ol>
 */
public final class ExportVerifier {

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final HashChainService hashChainService = new HashChainService(canonicalJson);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String signingKey;

    public ExportVerifier() {
        this(null);
    }

    public ExportVerifier(String signingKey) {
        this.signingKey = signingKey == null || signingKey.isBlank() ? null : signingKey;
    }

    public Report verify(Path bundleFile) throws IOException {
        return verify(mapper.readTree(Files.readString(bundleFile)));
    }

    public Report verify(String bundleJson) throws IOException {
        return verify(mapper.readTree(bundleJson));
    }

    public Report verify(JsonNode bundle) {
        List<String> findings = new ArrayList<>();
        if (bundle == null || !bundle.isObject()) {
            return new Report(false, 0, 0, 0, List.of("bundle is not a JSON object"));
        }

        boolean bundleHashValid = checkBundleHash(bundle, findings);
        checkBundleSignature(bundle, findings);

        JsonNode records = bundle.get("records");
        if (records == null || !records.isArray()) {
            findings.add("bundle has no records array");
            return new Report(bundleHashValid, 0, 0, 0, findings);
        }

        int rehashed = 0;
        int skipped = 0;
        for (JsonNode record : records) {
            if (isRedacted(record)) {
                // Cannot re-derive a hash of a value we were not given. Reported, not failed.
                skipped++;
                continue;
            }
            rehashed++;
            checkContentHash(record, findings);
        }
        return new Report(bundleHashValid, records.size(), rehashed, skipped, findings);
    }

    private boolean checkBundleHash(JsonNode bundle, List<String> findings) {
        JsonNode declared = bundle.get("bundleHash");
        if (declared == null || !declared.isTextual() || declared.asText().isBlank()) {
            findings.add("bundleHash is missing");
            return false;
        }
        String recomputed = ExportBundleService.hashOf(bundle, canonicalJson);
        if (!recomputed.equals(declared.asText())) {
            findings.add("bundleHash mismatch: file declares " + declared.asText()
                    + " but its contents hash to " + recomputed);
            return false;
        }
        return true;
    }

    private void checkBundleSignature(JsonNode bundle, List<String> findings) {
        JsonNode declared = bundle.get("bundleSignature");
        if (signingKey == null) {
            return;
        }
        if (declared == null || !declared.isTextual() || declared.asText().isBlank()) {
            findings.add("bundleSignature is missing");
            return;
        }
        JsonNode hash = bundle.get("bundleHash");
        if (hash == null || !hash.isTextual()) {
            return;
        }
        String expected = HmacSha256.hex(signingKey, hash.asText());
        if (!expected.equals(declared.asText())) {
            findings.add("bundleSignature mismatch");
        }
    }

    private void checkContentHash(JsonNode record, List<String> findings) {
        try {
            ChainInput input = new ChainInput(
                    record.path("sequence").asLong(),
                    text(record, "eventType"),
                    text(record, "actorId"),
                    text(record, "resourceType"),
                    text(record, "resourceId"),
                    record.get("payload"),
                    Instant.parse(text(record, "occurredAt")),
                    Instant.parse(text(record, "recordedAt")),
                    text(record, "previousHash"));
            String recomputed = hashChainService.contentHash(input);
            String declared = text(record, "contentHash");
            if (!recomputed.equals(declared)) {
                findings.add("contentHash mismatch at sequence " + input.sequence()
                        + ": file declares " + declared + " but its fields hash to " + recomputed);
            }
        } catch (DateTimeParseException | NullPointerException e) {
            findings.add("record at sequence " + record.path("sequence").asLong()
                    + " is missing fields required to re-hash it");
        }
    }

    private static boolean isRedacted(JsonNode record) {
        JsonNode redactedFields = record.get("redactedFields");
        return redactedFields != null && redactedFields.isArray() && !redactedFields.isEmpty();
    }

    private static String text(JsonNode record, String field) {
        JsonNode value = record.get(field);
        if (value == null || !value.isTextual()) {
            throw new NullPointerException("missing field " + field);
        }
        return value.asText();
    }

    /**
     * Outcome of a bundle check. {@code intact()} is the single answer a recipient wants: the file is
     * unchanged since export and every record it could re-hash matched.
     */
    public record Report(
            boolean bundleHashValid,
            int recordsInBundle,
            int recordsRehashed,
            int recordsSkippedBecauseRedacted,
            List<String> findings
    ) {

        public boolean intact() {
            return bundleHashValid && findings.isEmpty();
        }

        @Override
        public String toString() {
            return """
                    bundleHashValid=%s intact=%s
                    records=%d rehashed=%d skippedRedacted=%d
                    findings=%s"""
                    .formatted(bundleHashValid, intact(), recordsInBundle, recordsRehashed,
                            recordsSkippedBecauseRedacted,
                            findings.isEmpty() ? "none" : findings);
        }
    }

    /** {@code java -cp app.jar com.auditlog.domain.ExportVerifier bundle.json} */
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: ExportVerifier <bundle.json> [signing-key]");
            System.exit(2);
        }
        String key = args.length == 2 ? args[1] : System.getenv("AUDIT_EXPORT_SIGNING_KEY");
        Report report = new ExportVerifier(key).verify(Path.of(args[0]));
        System.out.println(report);
        System.exit(report.intact() ? 0 : 1);
    }
}
