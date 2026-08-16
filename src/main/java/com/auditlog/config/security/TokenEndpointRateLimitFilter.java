package com.auditlog.config.security;

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

/**
 * Per-IP sliding minute windows for the public token mint and for ingest writes. Other paths are
 * not limited here: they already require a credential.
 */
public class TokenEndpointRateLimitFilter extends OncePerRequestFilter {

    private final int tokenPerMinute;
    private final int writePerMinute;
    private final JsonAuthHandlers handlers;
    private final Map<String, Window> tokenWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> writeWindows = new ConcurrentHashMap<>();

    public TokenEndpointRateLimitFilter(int tokenPerMinute, int writePerMinute, JsonAuthHandlers handlers) {
        this.tokenPerMinute = tokenPerMinute;
        this.writePerMinute = writePerMinute;
        this.handlers = handlers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return bucket(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Bucket bucket = bucket(request);
        int limit = bucket == Bucket.TOKEN ? tokenPerMinute : writePerMinute;
        Map<String, Window> windows = bucket == Bucket.TOKEN ? tokenWindows : writeWindows;
        String ip = clientIp(request);
        Instant now = Instant.now();
        Window window = windows.compute(ip, (key, existing) -> {
            if (existing == null || now.isAfter(existing.resetAt)) {
                return new Window(1, now.plusSeconds(60));
            }
            return new Window(existing.count + 1, existing.resetAt);
        });
        if (window.count > limit) {
            handlers.write(response, 429, "Too Many Requests", "RATE_LIMITED");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static Bucket bucket(HttpServletRequest request) {
        if (HttpMethod.POST.matches(request.getMethod()) && "/auth/token".equals(request.getServletPath())) {
            return Bucket.TOKEN;
        }
        if (HttpMethod.POST.matches(request.getMethod()) && "/audit/events".equals(request.getServletPath())) {
            return Bucket.WRITE;
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private enum Bucket {
        TOKEN,
        WRITE
    }

    private record Window(int count, Instant resetAt) {
    }
}
