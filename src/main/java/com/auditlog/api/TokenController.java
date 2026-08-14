package com.auditlog.api;

import com.auditlog.api.error.ErrorResponse;
import com.auditlog.domain.TokenService;
import com.auditlog.domain.TokenService.InvalidClientException;
import com.auditlog.domain.TokenService.InvalidScopeException;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Auth")
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Operation(summary = "Issue a short-lived JWT (OAuth 2.0 client credentials). Prototype only.")
    @PostMapping(path = "/auth/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TokenResponse tokenJson(@Valid @RequestBody TokenRequest request) {
        TokenService.IssuedToken issued = tokenService.issue(
                request.clientId(),
                request.clientSecret(),
                request.scope()
        );
        return TokenResponse.from(issued);
    }

    @Operation(summary = "Issue a short-lived JWT using form-encoded client credentials.")
    @PostMapping(path = "/auth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public TokenResponse tokenForm(
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam(value = "scope", required = false) String scope
    ) {
        TokenService.IssuedToken issued = tokenService.issue(clientId, clientSecret, scope);
        return TokenResponse.from(issued);
    }

    @ExceptionHandler(InvalidClientException.class)
    public ResponseEntity<ErrorResponse> invalidClient() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("Unauthorized", "UNAUTHORIZED"));
    }

    @ExceptionHandler(InvalidScopeException.class)
    public ResponseEntity<ErrorResponse> invalidScope(InvalidScopeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ex.getMessage(), "INVALID_SCOPE"));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenRequest(
            @JsonProperty("client_id")
            @JsonAlias("clientId")
            @NotBlank String clientId,
            @JsonProperty("client_secret")
            @JsonAlias("clientSecret")
            @NotBlank String clientSecret,
            String scope
    ) {
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            String scope
    ) {
        static TokenResponse from(TokenService.IssuedToken issued) {
            return new TokenResponse(issued.accessToken(), issued.tokenType(), issued.expiresIn(), issued.scope());
        }
    }
}
