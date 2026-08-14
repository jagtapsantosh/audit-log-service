package com.auditlog.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

public class TokenEndpointRateLimitFilter extends OncePerRequestFilter {

    private final int limitPerMinute;
    private final JsonAuthHandlers handlers;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public TokenEndpointRateLimitFilter(int limitPerMinute, JsonAuthHandlers handlers) {
        this.limitPerMinute = limitPerMinute;
        this.handlers = handlers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && "/auth/token".equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String ip = clientIp(request);
        Instant now = Instant.now();
        Window window = windows.compute(ip, (key, existing) -> {
            if (existing == null || now.isAfter(existing.resetAt)) {
                return new Window(1, now.plusSeconds(60));
            }
            return new Window(existing.count + 1, existing.resetAt);
        });
        if (window.count > limitPerMinute) {
            handlers.write(response, 429, "Too Many Requests", "RATE_LIMITED");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private record Window(int count, Instant resetAt) {
    }
}
