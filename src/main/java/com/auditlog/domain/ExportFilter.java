package com.auditlog.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What an export was asked for. Recorded inside the bundle and covered by {@code bundleHash}, so a
 * recipient cannot be told the file is "all events for user-123" and be handed a different slice.
 *
 * <p>Null fields are omitted from the bundle JSON, which is why the verifier canonicalizes the
 * document as written rather than a fixed field list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportFilter(String actorId, String resourceType, String resourceId) {

    public static ExportFilter forActor(String actorId) {
        return new ExportFilter(actorId, null, null);
    }

    public static ExportFilter forResource(String resourceType, String resourceId) {
        return new ExportFilter(null, resourceType, resourceId);
    }

    /** {@code @JsonIgnore} because the bundle format is fixed and covered by {@code bundleHash}. */
    @JsonIgnore
    public boolean isEmpty() {
        return isBlank(actorId) && isBlank(resourceId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
