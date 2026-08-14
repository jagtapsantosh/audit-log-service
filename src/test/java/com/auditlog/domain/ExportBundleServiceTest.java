package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExportBundleServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-08-14T11:30:00Z");
    private static final Instant RECORDED = Instant.parse("2026-08-14T11:37:00Z");

    @Mock
    private AuditRecordStore recordStore;

    @Mock
    private AuditRedactionStore redactionStore;

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final RedactionOverlay overlay = new RedactionOverlay();
    private final HashChainService hashChainService = new HashChainService(canonicalJson);

    private ObjectMapper objectMapper;
    private ExportBundleService exportBundleService;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        exportBundleService = new ExportBundleService(recordStore, redactionStore, overlay,
                canonicalJson, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void bundlesEveryRecordForAnActor() {
        stubRecords(record(1L, 1L, "{\"ip\":\"10.0.0.1\"}"), record(2L, 9L, "{\"ip\":\"10.0.0.2\"}"));
        stubNoRedactions();

        ExportBundle bundle = exportBundleService.export(ExportFilter.forActor("user-123"));

        assertThat(bundle.exportVersion()).isEqualTo("1.0");
        assertThat(bundle.exportedAt()).isEqualTo(NOW);
        assertThat(bundle.genesisHash()).isEqualTo(HashChainService.GENESIS_HASH);
        assertThat(bundle.filter().actorId()).isEqualTo("user-123");
        assertThat(bundle.records()).hasSize(2);
        assertThat(bundle.bundleHash()).hasSize(64);
    }

    @Test
    void sequenceGapsAreExpectedInASparseSlice() {
        // Sequences 1 and 9: the records between them belong to other actors.
        stubRecords(record(1L, 1L, "{\"a\":1}"), record(2L, 9L, "{\"a\":2}"));
        stubNoRedactions();

        ExportBundle bundle = exportBundleService.export(ExportFilter.forActor("user-123"));

        assertThat(bundle.records()).extracting(ExportRecord::sequence).containsExactly(1L, 9L);
    }

    @Test
    void copiesTheServerHashesVerbatim() {
        AuditRecord record = record(1L, 4L, "{\"a\":1}");
        stubRecords(record);
        stubNoRedactions();

        ExportRecord exported = exportBundleService.export(ExportFilter.forActor("user-123"))
                .records()
                .getFirst();

        assertThat(exported.contentHash()).isEqualTo(record.contentHash());
        assertThat(exported.previousHash()).isEqualTo(record.previousHash());
    }

    @Test
    void appliesRedactionsToTheExportedPayload() {
        stubRecords(record(1L, 1L, "{\"accountNumber\":\"1234\",\"ip\":\"10.0.0.1\"}"));
        when(redactionStore.findByRecordIds(List.of(1L))).thenReturn(Map.of(
                1L, List.of(new Redaction(5L, 1L, "accountNumber", RECORDED, "ops-admin", "GDPR"))));

        ExportRecord exported = exportBundleService.export(ExportFilter.forActor("user-123"))
                .records()
                .getFirst();

        assertThat(exported.payload().get("accountNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(exported.redactedFields()).containsExactly("accountNumber");
        assertThat(exported.isRedacted()).isTrue();
    }

    @Test
    void anUnredactedRecordInTheBundleStillRehashesToItsContentHash() {
        AuditRecord record = record(1L, 1L, "{\"ip\":\"10.0.0.1\"}");
        stubRecords(record);
        stubNoRedactions();

        ExportRecord exported = exportBundleService.export(ExportFilter.forActor("user-123"))
                .records()
                .getFirst();

        String recomputed = hashChainService.contentHash(new ChainInput(
                exported.sequence(), exported.eventType(), exported.actorId(), exported.resourceType(),
                exported.resourceId(), exported.payload(), exported.occurredAt(), exported.recordedAt(),
                exported.previousHash()));
        assertThat(recomputed).isEqualTo(record.contentHash());
    }

    @Test
    void theBundleHashCoversTheWholeDocument() {
        stubRecords(record(1L, 1L, "{\"ip\":\"10.0.0.1\"}"));
        stubNoRedactions();
        ExportBundle bundle = exportBundleService.export(ExportFilter.forActor("user-123"));

        // Same document, one edited payload value: the hash must move.
        ExportRecord edited = new ExportRecord(
                bundle.records().getFirst().sequence(),
                bundle.records().getFirst().eventType(),
                bundle.records().getFirst().actorId(),
                bundle.records().getFirst().resourceType(),
                bundle.records().getFirst().resourceId(),
                bundle.records().getFirst().occurredAt(),
                bundle.records().getFirst().recordedAt(),
                bundle.records().getFirst().contentHash(),
                bundle.records().getFirst().previousHash(),
                canonicalJson.parse("{\"ip\":\"10.0.0.9\"}"),
                List.of());
        ExportBundle tampered = new ExportBundle(bundle.exportVersion(), bundle.exportedAt(),
                bundle.filter(), bundle.genesisHash(), List.of(edited), null);

        assertThat(exportBundleService.bundleHash(tampered)).isNotEqualTo(bundle.bundleHash());
    }

    @Test
    void theBundleHashChangesWhenTheFilterIsRestated() {
        stubRecords(record(1L, 1L, "{\"ip\":\"10.0.0.1\"}"));
        stubNoRedactions();
        ExportBundle bundle = exportBundleService.export(ExportFilter.forActor("user-123"));

        ExportBundle relabelled = new ExportBundle(bundle.exportVersion(), bundle.exportedAt(),
                ExportFilter.forActor("user-999"), bundle.genesisHash(), bundle.records(), null);

        assertThat(exportBundleService.bundleHash(relabelled)).isNotEqualTo(bundle.bundleHash());
    }

    @Test
    void theBundleHashIsStableForTheSameContent() {
        stubRecords(record(1L, 1L, "{\"ip\":\"10.0.0.1\"}"));
        stubNoRedactions();

        ExportBundle first = exportBundleService.export(ExportFilter.forActor("user-123"));
        ExportBundle second = exportBundleService.export(ExportFilter.forActor("user-123"));

        assertThat(first.bundleHash()).isEqualTo(second.bundleHash());
    }

    @Test
    void theBundleCarriesExactlyTheDocumentedFields() {
        stubRecords(record(1L, 1L, "{\"ip\":\"10.0.0.1\"}"));
        stubNoRedactions();

        JsonNode json = objectMapper.valueToTree(
                exportBundleService.export(ExportFilter.forActor("user-123")));

        // Derived accessors such as isRedacted()/isEmpty() must not leak into the format: the file is
        // a published contract and every byte of it is covered by bundleHash.
        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "exportVersion", "exportedAt", "filter", "genesisHash", "records", "bundleHash");
        assertThat(fieldNames(json.get("filter"))).containsExactly("actorId");
        assertThat(fieldNames(json.get("records").get(0))).containsExactlyInAnyOrder(
                "sequence", "eventType", "actorId", "resourceType", "resourceId", "occurredAt",
                "recordedAt", "contentHash", "previousHash", "payload", "redactedFields");
    }

    @Test
    void anEmptyResultIsStillASealedBundle() {
        stubRecords();
        stubNoRedactions();

        ExportBundle bundle = exportBundleService.export(ExportFilter.forActor("nobody"));

        assertThat(bundle.records()).isEmpty();
        assertThat(bundle.bundleHash()).hasSize(64);
    }

    @Test
    void requiresAnActorOrAResource() {
        assertThatThrownBy(() -> exportBundleService.export(new ExportFilter(null, "SESSION", null)))
                .isInstanceOf(InvalidExportRequestException.class)
                .satisfies(thrown -> assertThat(((InvalidExportRequestException) thrown).code())
                        .isEqualTo("EXPORT_SUBJECT_REQUIRED"));

        assertThatThrownBy(() -> exportBundleService.export(new ExportFilter(" ", null, " ")))
                .isInstanceOf(InvalidExportRequestException.class);

        verify(recordStore, never()).findForExport(any(), anyInt());
    }

    @Test
    void refusesToBundleMoreRecordsThanTheLimit() {
        List<AuditRecord> tooMany = java.util.stream.LongStream
                .rangeClosed(1, ExportBundleService.MAX_RECORDS + 1)
                .mapToObj(i -> record(i, i, "{\"a\":1}"))
                .toList();
        when(recordStore.findForExport(any(), anyInt())).thenReturn(tooMany);

        assertThatThrownBy(() -> exportBundleService.export(ExportFilter.forActor("user-123")))
                .isInstanceOf(InvalidExportRequestException.class)
                .satisfies(thrown -> assertThat(((InvalidExportRequestException) thrown).code())
                        .isEqualTo("EXPORT_TOO_LARGE"));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void stubRecords(AuditRecord... records) {
        when(recordStore.findForExport(any(), anyInt())).thenReturn(List.of(records));
    }

    private void stubNoRedactions() {
        when(redactionStore.findByRecordIds(any())).thenReturn(Map.of());
    }

    private AuditRecord record(long id, long sequence, String payloadJson) {
        var payload = canonicalJson.parse(payloadJson);
        String previousHash = sequence == 1 ? HashChainService.GENESIS_HASH : "b".repeat(64);
        String contentHash = hashChainService.contentHash(new ChainInput(sequence, "ACCOUNT_VIEWED",
                "user-123", "CLIENT_ACCOUNT", "acct-1", payload, OCCURRED, RECORDED, previousHash));
        return new AuditRecord(id, sequence, "ACCOUNT_VIEWED", "user-123", "CLIENT_ACCOUNT", "acct-1",
                payload, OCCURRED, RECORDED, contentHash, previousHash);
    }
}
