package com.auditlog.config.security;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

public final class JwtScopeAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final String requiredAuthority;

    public JwtScopeAuthorizationManager(String scope) {
        this.requiredAuthority = "SCOPE_" + scope;
    }

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (!(auth instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }
        boolean allowed = jwt.getAuthorities().stream()
                .anyMatch(granted -> requiredAuthority.equals(granted.getAuthority()));
        return new AuthorizationDecision(allowed);
    }
}
