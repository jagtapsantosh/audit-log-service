package com.auditlog.api.dto;

import com.auditlog.domain.ChainVerificationResult;
import com.auditlog.domain.ChainVerificationResult.ViolationType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "VerifyResponse", description = "Result of walking the full hash chain")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifyResponse(
        @Schema(description = "True when every record re-hashes and links correctly. An empty chain "
                + "is intact.") boolean intact,
        @Schema(description = "Records stored in the chain, including archived ones") long totalRecords,
        @Schema(description = "Omitted when the chain is intact") Violation firstViolation
) {

    public static VerifyResponse from(ChainVerificationResult result) {
        return new VerifyResponse(
                result.intact(),
                result.totalRecords(),
                result.violation().map(Violation::from).orElse(null));
    }

    @Schema(name = "ChainViolation", description = "The first inconsistency found, by ascending sequence")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Violation(
            long sequence,
            Long recordId,
            ViolationType violationType,
            @Schema(description = "Hash the stored content implies; null for non-hash violations")
            String expectedHash,
            @Schema(description = "Hash currently stored on the row; null for non-hash violations")
            String actualHash,
            String detail
    ) {

        static Violation from(ChainVerificationResult.ChainViolation violation) {
            return new Violation(
                    violation.sequence(),
                    violation.recordId(),
                    violation.violationType(),
                    violation.expectedHash(),
                    violation.actualHash(),
                    violation.detail());
        }
    }
}
