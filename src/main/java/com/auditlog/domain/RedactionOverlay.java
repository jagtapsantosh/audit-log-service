package com.auditlog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Applies redactions on the way out.
 *
 * <p>This is the whole reason the hash chain survives redaction: the stored payload is never
 * touched, so {@code contentHash} still matches a re-hash of the record. Masking happens on a deep
 * copy, per read.
 *
 * <p>A field path is a dotted path into the payload object, e.g. {@code accountNumber} or
 * {@code customer.ssn}. Array indexing is not supported in v1 — a path that would have to traverse
 * an array is treated as unknown rather than silently ignored.
 */
@Component
public class RedactionOverlay {

    /** Replacement value shown to readers. */
    public static final String REDACTED = "[REDACTED]";

    private static final String PATH_SEPARATOR = ".";
    private static final int MAX_PATH_LENGTH = 255;

    public AuditRecordView apply(AuditRecord record, List<Redaction> redactions) {
        if (redactions == null || redactions.isEmpty()) {
            return AuditRecordView.of(record);
        }
        List<String> paths = redactions.stream()
                .map(Redaction::fieldPath)
                .distinct()
                .sorted()
                .toList();
        return new AuditRecordView(record, mask(record.payload(), paths), paths);
    }

    /** Returns a copy of {@code payload} with every known path replaced by {@link #REDACTED}. */
    public JsonNode mask(JsonNode payload, Collection<String> fieldPaths) {
        if (payload == null || !payload.isObject() || fieldPaths.isEmpty()) {
            return payload;
        }
        ObjectNode masked = (ObjectNode) payload.deepCopy();
        for (String path : fieldPaths) {
            String[] segments = path.split("\\" + PATH_SEPARATOR);
            ObjectNode parent = resolveParent(masked, segments);
            if (parent != null && parent.has(segments[segments.length - 1])) {
                parent.put(segments[segments.length - 1], REDACTED);
            }
        }
        return masked;
    }

    /** True when the path addresses a field that actually exists in the original payload. */
    public boolean pathExists(JsonNode payload, String path) {
        if (payload == null || !payload.isObject() || !isSyntacticallyValid(path)) {
            return false;
        }
        String[] segments = path.split("\\" + PATH_SEPARATOR);
        ObjectNode parent = resolveParent((ObjectNode) payload, segments);
        return parent != null && parent.has(segments[segments.length - 1]);
    }

    /**
     * Syntax check that is independent of any payload, so a malformed path is reported as malformed
     * rather than as "unknown field".
     */
    public boolean isSyntacticallyValid(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        if (path.startsWith(PATH_SEPARATOR) || path.endsWith(PATH_SEPARATOR) || path.contains("..")) {
            return false;
        }
        // Array indexing is out of scope for v1; reject it rather than half-supporting it.
        return path.indexOf('[') < 0 && path.indexOf(']') < 0;
    }

    /** Walks all but the last segment; null when the path does not describe nested objects. */
    private static ObjectNode resolveParent(ObjectNode root, String[] segments) {
        ObjectNode current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonNode next = current.get(segments[i]);
            if (next == null || !next.isObject()) {
                return null;
            }
            current = (ObjectNode) next;
        }
        return current;
    }

    /** Convenience for callers that already hold paths as strings. */
    public List<String> sortedDistinct(Collection<String> fieldPaths) {
        return fieldPaths.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }
}
