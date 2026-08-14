package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the self-contained export bundle for one actor or resource.
 *
 * <p>A filtered export is a <em>subsequence</em> of the global chain, not a chain of its own: the
 * sequence numbers have gaps and most {@code previousHash} values point at records that are not in
 * the file. So the bundle's integrity claim is deliberately narrower than the service's — a
 * {@code bundleHash} over the whole document proves the file has not been altered since export, and a
 * recipient can additionally re-hash any record that carries no redactions.
 */
@Service
public class ExportBundleService {

    /** Bound on one bundle; beyond this the caller should narrow the filter. */
    public static final int MAX_RECORDS = 10_000;

    private static final String BUNDLE_HASH_FIELD = "bundleHash";

    private static final Logger log = LoggerFactory.getLogger(ExportBundleService.class);

    private final AuditRecordStore recordStore;
    private final AuditRedactionStore redactionStore;
    private final RedactionOverlay overlay;
    private final CanonicalJson canonicalJson;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExportBundleService(
            AuditRecordStore recordStore,
            AuditRedactionStore redactionStore,
            RedactionOverlay overlay,
            CanonicalJson canonicalJson,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.recordStore = recordStore;
        this.redactionStore = redactionStore;
        this.overlay = overlay;
        this.canonicalJson = canonicalJson;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ExportBundle export(ExportFilter filter) {
        if (filter == null || filter.isEmpty()) {
            throw new InvalidExportRequestException("EXPORT_SUBJECT_REQUIRED",
                    "export requires actorId or resourceId");
        }

        // One extra row is fetched purely to detect that the caller is over the limit.
        List<AuditRecord> records = recordStore.findForExport(filter, MAX_RECORDS + 1);
        if (records.size() > MAX_RECORDS) {
            throw new InvalidExportRequestException("EXPORT_TOO_LARGE",
                    "export matches more than " + MAX_RECORDS + " records; narrow the filter");
        }

        Map<Long, List<Redaction>> redactions = redactionStore.findByRecordIds(
                records.stream().map(AuditRecord::id).toList());

        List<ExportRecord> exported = records.stream()
                .map(record -> ExportRecord.from(
                        overlay.apply(record, redactions.getOrDefault(record.id(), List.of()))))
                .toList();

        ExportBundle unsealed = new ExportBundle(
                ExportBundle.CURRENT_VERSION,
                CanonicalJson.canonicalInstant(clock.instant()),
                filter,
                HashChainService.GENESIS_HASH,
                exported,
                null);

        ExportBundle bundle = unsealed.sealedWith(bundleHash(unsealed));
        log.info("Exported {} record(s) for filter {} bundleHash={}",
                exported.size(), filter, bundle.bundleHash());
        return bundle;
    }

    /**
     * SHA-256 over the canonical form of the bundle document with {@code bundleHash} removed. The
     * recipient-side {@link ExportVerifier} recomputes it the same way from the file it received.
     */
    public String bundleHash(ExportBundle bundle) {
        return hashOf(objectMapper.valueToTree(bundle));
    }

    /** Shared with the verifier: canonicalize the document minus its own hash field, then digest. */
    static String hashOf(JsonNode bundleJson, CanonicalJson canonicalJson) {
        ObjectNode copy = (ObjectNode) bundleJson.deepCopy();
        copy.remove(BUNDLE_HASH_FIELD);
        return Sha256.hex(canonicalJson.serializeToBytes(copy));
    }

    private String hashOf(JsonNode bundleJson) {
        return hashOf(bundleJson, canonicalJson);
    }
}
