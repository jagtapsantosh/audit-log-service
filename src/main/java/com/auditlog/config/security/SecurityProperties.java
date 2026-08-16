package com.auditlog.config.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audit.security")
public record SecurityProperties(
        String pepper,
        Jwt jwt,
        List<ApiKeyEntry> apiKeys,
        List<OauthClient> oauthClients,
        RateLimit rateLimit,
        String exportSigningKey,
        Boolean localTokenEndpoint,
        Integer maxRequestBytes
) {

    public SecurityProperties {
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
        oauthClients = oauthClients == null ? List.of() : List.copyOf(oauthClients);
        rateLimit = rateLimit == null ? new RateLimit(10, 120) : rateLimit;
        if (exportSigningKey == null) {
            exportSigningKey = "";
        }
        if (localTokenEndpoint == null) {
            localTokenEndpoint = Boolean.TRUE;
        }
        if (maxRequestBytes == null || maxRequestBytes <= 0) {
            maxRequestBytes = 128 * 1024;
        }
    }

    public boolean localTokenEndpointEnabled() {
        return Boolean.TRUE.equals(localTokenEndpoint);
    }

    public record Jwt(String secret, Duration ttl, String issuer, String jwkSetUri) {
        public Jwt {
            if (ttl == null) {
                ttl = Duration.ofMinutes(15);
            }
            if (issuer == null || issuer.isBlank()) {
                issuer = "audit-log-service";
            }
            if (jwkSetUri != null && jwkSetUri.isBlank()) {
                jwkSetUri = null;
            }
        }

        public boolean usesJwks() {
            return jwkSetUri != null;
        }
    }

    public record ApiKeyEntry(String clientId, String keyHash, List<String> scopes) {
        public ApiKeyEntry {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    public record OauthClient(String clientId, String secretHash, List<String> scopes) {
        public OauthClient {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    public record RateLimit(int tokenPerMinute, int writePerMinute) {
        public RateLimit {
            if (tokenPerMinute <= 0) {
                tokenPerMinute = 10;
            }
            if (writePerMinute <= 0) {
                writePerMinute = 120;
            }
        }
    }
}
