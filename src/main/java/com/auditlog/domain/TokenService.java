package com.auditlog.domain;

import com.auditlog.config.security.CredentialHasher;
import com.auditlog.config.security.SecurityProperties;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final SecurityProperties properties;
    private final JwtEncoder jwtEncoder;

    public TokenService(SecurityProperties properties, JwtEncoder jwtEncoder) {
        this.properties = properties;
        this.jwtEncoder = jwtEncoder;
    }

    public IssuedToken issue(String clientId, String clientSecret, String requestedScope) {
        SecurityProperties.OauthClient client = properties.oauthClients().stream()
                .filter(entry -> entry.clientId().equals(clientId))
                .filter(entry -> CredentialHasher.matches(clientSecret, properties.pepper(), entry.secretHash()))
                .findFirst()
                .orElseThrow(InvalidClientException::new);

        List<String> granted = resolveScopes(client.scopes(), requestedScope);
        Instant now = Instant.now();
        Instant expires = now.plus(properties.jwt().ttl());
        String scope = String.join(" ", granted);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .issuedAt(now)
                .expiresAt(expires)
                .subject(client.clientId())
                .claim("scope", scope)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();

        return new IssuedToken(token, "Bearer", properties.jwt().ttl().toSeconds(), scope);
    }

    private static List<String> resolveScopes(List<String> allowed, String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return allowed;
        }
        Set<String> allowedSet = new LinkedHashSet<>(allowed);
        List<String> requested = Arrays.stream(requestedScope.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
        for (String scope : requested) {
            if (!allowedSet.contains(scope)) {
                throw new InvalidScopeException(scope);
            }
        }
        return requested;
    }

    public record IssuedToken(String accessToken, String tokenType, long expiresIn, String scope) {
    }

    public static final class InvalidClientException extends RuntimeException {
        public InvalidClientException() {
            super("Invalid client credentials");
        }
    }

    public static final class InvalidScopeException extends RuntimeException {
        public InvalidScopeException(String scope) {
            super("Invalid scope: " + scope);
        }
    }
}
