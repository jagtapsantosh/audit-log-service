package com.auditlog.domain;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic JSON serialization used as the hash pre-image.
 *
 * <p>The same logical record must produce identical bytes at write time and at verify time, even
 * though the payload makes a round trip through PostgreSQL {@code jsonb}, which discards key order
 * and re-renders numbers. Rules:
 *
 * <ul>
 *   <li>object keys sorted lexicographically, recursively; array order preserved
 *   <li>no insignificant whitespace; UTF-8 bytes
 *   <li>numbers written as plain decimal with trailing zeros stripped, so {@code 1}, {@code 1.0}
 *       and {@code 1e0} all canonicalize to {@code 1}
 *   <li>instants truncated to microseconds (PostgreSQL {@code timestamptz} resolution) and rendered
 *       with {@link Instant#toString()}
 * </ul>
 */
@Component
public class CanonicalJson {

    /** PostgreSQL {@code timestamptz} keeps microseconds; nanos would be lost on the way back. */
    public static final ChronoUnit CLOCK_PRECISION = ChronoUnit.MICROS;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    public ObjectNode newObject() {
        return JsonNodeFactory.instance.objectNode();
    }

    public JsonNode emptyObject() {
        return newObject();
    }

    /** Truncates to the precision the database can store, so a re-read hashes identically. */
    public static Instant canonicalInstant(Instant instant) {
        return instant.truncatedTo(CLOCK_PRECISION);
    }

    public JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not valid JSON", e);
        }
    }

    public String serialize(JsonNode node) {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = mapper.createGenerator(writer)) {
            write(node, generator);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to canonicalize JSON", e);
        }
        return writer.toString();
    }

    public byte[] serializeToBytes(JsonNode node) {
        return serialize(node).getBytes(StandardCharsets.UTF_8);
    }

    private void write(JsonNode node, JsonGenerator generator) throws IOException {
        if (node == null || node.isNull() || node.isMissingNode()) {
            generator.writeNull();
        } else if (node.isObject()) {
            generator.writeStartObject();
            for (String name : sortedFieldNames(node)) {
                generator.writeFieldName(name);
                write(node.get(name), generator);
            }
            generator.writeEndObject();
        } else if (node.isArray()) {
            generator.writeStartArray();
            for (JsonNode element : node) {
                write(element, generator);
            }
            generator.writeEndArray();
        } else if (node.isNumber()) {
            generator.writeNumber(canonicalNumber(node.decimalValue()));
        } else if (node.isBoolean()) {
            generator.writeBoolean(node.booleanValue());
        } else {
            generator.writeString(node.asText());
        }
    }

    private static List<String> sortedFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        names.sort(String::compareTo);
        return names;
    }

    private static String canonicalNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }
}
