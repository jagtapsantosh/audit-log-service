package com.auditlog.domain;

import java.util.List;

/**
 * The closed set that Scenario C treats as "access to client account data".
 *
 * <p>These values are not caller-overridable: a regulator cannot widen the report to logins or
 * narrow it past this enum. Changing the set is a product decision, not a query parameter.
 */
public final class AccessScope {

    public static final String RESOURCE_TYPE = "CLIENT_ACCOUNT";

    public static final List<String> EVENT_TYPES = List.of(
            "ACCOUNT_VIEWED",
            "ACCOUNT_UPDATED",
            "STATEMENT_DOWNLOADED",
            "PERMISSION_GRANTED");

    public static final String VERIFICATION_HINT =
            "GET /audit/verify must be intact. chainHeadHash must equal contentHash of the current "
                    + "chain head if no writes occurred after generatedAt.";

    private AccessScope() {
    }
}
