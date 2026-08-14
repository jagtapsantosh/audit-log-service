package com.auditlog.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final SecurityProperties properties;
    private final AuthenticationEntryPoint entryPoint;

    public ApiKeyAuthenticationFilter(SecurityProperties properties, AuthenticationEntryPoint entryPoint) {
        this.properties = properties;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        SecurityProperties.ApiKeyEntry match = properties.apiKeys().stream()
                .filter(entry -> CredentialHasher.matches(apiKey, properties.pepper(), entry.keyHash()))
                .findFirst()
                .orElse(null);

        if (match == null) {
            entryPoint.commence(
                    request,
                    response,
                    new org.springframework.security.authentication.BadCredentialsException("Invalid API key"));
            return;
        }

        List<SimpleGrantedAuthority> authorities = match.scopes().stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
        var authentication = new ApiKeyAuthenticationToken(match.clientId(), authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) && request.getHeader(HEADER) == null;
    }
}
