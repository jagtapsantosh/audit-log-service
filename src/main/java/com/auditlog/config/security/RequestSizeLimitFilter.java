package com.auditlog.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects requests that declare a body larger than {@code audit.http.max-request-bytes}. */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final int maxRequestBytes;
    private final JsonAuthHandlers handlers;

    public RequestSizeLimitFilter(int maxRequestBytes, JsonAuthHandlers handlers) {
        this.maxRequestBytes = maxRequestBytes;
        this.handlers = handlers;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxRequestBytes) {
            handlers.write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "Request body exceeds " + maxRequestBytes + " bytes", "PAYLOAD_TOO_LARGE");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
