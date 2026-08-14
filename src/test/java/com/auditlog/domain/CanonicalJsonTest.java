package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical form is the hash pre-image, so these rules are part of the tamper-evidence
 * guarantee: if serialization is not byte-identical on write and on verify, an honest record looks
 * tampered.
 */
class CanonicalJsonTest {

    private final CanonicalJson canonicalJson = new CanonicalJson();

    @Test
    @DisplayName("object keys are sorted, recursively, and array order is preserved")
    void sortsKeysRecursivelyAndPreservesArrayOrder() {
        JsonNode node = canonicalJson.parse(
                "{\"b\":1,\"a\":{\"z\":true,\"y\":[3,1,2]},\"c\":[{\"n\":1,\"m\":2}]}");

        assertThat(canonicalJson.serialize(node))
                .isEqualTo("{\"a\":{\"y\":[3,1,2],\"z\":true},\"b\":1,\"c\":[{\"m\":2,\"n\":1}]}");
    }

    @Test
    @DisplayName("key order in the input does not change the bytes")
    void keyOrderIsIrrelevant() {
        String first = canonicalJson.serialize(canonicalJson.parse("{\"a\":1,\"b\":2}"));
        String second = canonicalJson.serialize(canonicalJson.parse("{\"b\":2,\"a\":1}"));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("numbers normalize so jsonb round-tripping cannot change the hash")
    void normalizesNumberRepresentations() {
        // PostgreSQL jsonb re-renders numbers: 1e2 comes back as 100 and 1.50 keeps its scale.
        // Stripping trailing zeros makes every spelling of the same value one canonical string.
        assertThat(canonicalJson.serialize(canonicalJson.parse("{\"n\":1}"))).isEqualTo("{\"n\":1}");
        assertThat(canonicalJson.serialize(canonicalJson.parse("{\"n\":1.0}"))).isEqualTo("{\"n\":1}");
        assertThat(canonicalJson.serialize(canonicalJson.parse("{\"n\":1.50}"))).isEqualTo("{\"n\":1.5}");
        assertThat(canonicalJson.serialize(canonicalJson.parse("{\"n\":1e2}"))).isEqualTo("{\"n\":100}");
        assertThat(canonicalJson.serialize(canonicalJson.parse("{\"n\":0.000}"))).isEqualTo("{\"n\":0}");
        assertThat(canonicalJson.serialize(canonicalJson.parse("{\"n\":-2.500}"))).isEqualTo("{\"n\":-2.5}");
    }

    @Test
    void serializesNullsBooleansAndStringsWithEscaping() {
        JsonNode node = canonicalJson.parse("{\"s\":\"a\\\"b\",\"t\":true,\"n\":null}");

        assertThat(canonicalJson.serialize(node)).isEqualTo("{\"n\":null,\"s\":\"a\\\"b\",\"t\":true}");
    }

    @Test
    void emptyObjectIsStableAndSerializesToBraces() {
        assertThat(canonicalJson.serialize(canonicalJson.emptyObject())).isEqualTo("{}");
    }

    @Test
    @DisplayName("instants truncate to the microsecond resolution PostgreSQL can store")
    void truncatesInstantsToDatabasePrecision() {
        Instant nanos = Instant.parse("2026-08-14T11:30:00.123456789Z");

        assertThat(CanonicalJson.canonicalInstant(nanos))
                .isEqualTo(Instant.parse("2026-08-14T11:30:00.123456Z"));
    }
}
