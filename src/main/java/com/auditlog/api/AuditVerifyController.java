package com.auditlog.api;

import com.auditlog.api.dto.VerifyResponse;
import com.auditlog.domain.AuditVerifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chain integrity endpoint. A broken chain is still a successful report, so this returns 200 with
 * {@code intact: false} rather than an error status; monitoring should alert on the body.
 */
@RestController
@Tag(name = "Chain integrity")
public class AuditVerifyController {

    private final AuditVerifyService verifyService;

    public AuditVerifyController(AuditVerifyService verifyService) {
        this.verifyService = verifyService;
    }

    @Operation(
            summary = "Verify the full hash chain",
            description = "Walks every record in sequence order, recomputes each content hash, and "
                    + "checks predecessor links. Reports the first inconsistency and its violation "
                    + "type. JWT only (scope audit.read): a leaked ingest API key must not be able "
                    + "to probe chain integrity.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/audit/verify")
    public VerifyResponse verify() {
        return VerifyResponse.from(verifyService.verify());
    }
}
