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
        RateLimit rateLimit
) {

    public SecurityProperties {
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
        oauthClients = oauthClients == null ? List.of() : List.copyOf(oauthClients);
        rateLimit = rateLimit == null ? new RateLimit(10) : rateLimit;
    }

    public record Jwt(String secret, Duration ttl, String issuer) {
        public Jwt {
            if (ttl == null) {
                ttl = Duration.ofMinutes(15);
            }
            if (issuer == null || issuer.isBlank()) {
                issuer = "audit-log-service";
            }
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

    public record RateLimit(int tokenPerMinute) {
        public RateLimit {
            if (tokenPerMinute <= 0) {
                tokenPerMinute = 10;
            }
        }
    }
}
