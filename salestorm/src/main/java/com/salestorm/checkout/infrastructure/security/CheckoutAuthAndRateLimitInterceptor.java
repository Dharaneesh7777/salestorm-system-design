package com.salestorm.checkout.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Interceptor providing JWT-less simple auth stub (X-Customer-Id header check)
 * and per-user rate limiting (1 req/s) for checkout endpoints.
 */
@Component
public class CheckoutAuthAndRateLimitInterceptor implements HandlerInterceptor {

    public static final String AUTH_CUSTOMER_ID_ATTR = "authenticatedCustomerId";
    private static final String CUSTOMER_HEADER = "X-Customer-Id";
    private static final String ALT_CUSTOMER_HEADER = "CustomerId";

    private final PerUserRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public CheckoutAuthAndRateLimitInterceptor(PerUserRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/v1/checkout")) {
            return true;
        }

        // 1. JWT-less Auth Stub: Check customer ID header
        String customerIdStr = request.getHeader(CUSTOMER_HEADER);
        if (customerIdStr == null || customerIdStr.isBlank()) {
            customerIdStr = request.getHeader(ALT_CUSTOMER_HEADER);
        }

        if (customerIdStr == null || customerIdStr.isBlank()) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "Unauthorized: Missing X-Customer-Id header", request.getRequestURI());
            return false;
        }

        Long customerId;
        try {
            customerId = Long.parseLong(customerIdStr.trim());
            if (customerId <= 0) {
                writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "Unauthorized: Invalid X-Customer-Id value", request.getRequestURI());
                return false;
            }
        } catch (NumberFormatException e) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED, "Unauthorized: Non-numeric X-Customer-Id header", request.getRequestURI());
            return false;
        }

        // 2. Per-User Rate Limiter (1 req/sec)
        if (!rateLimiter.tryAcquire(customerId)) {
            response.setHeader("Retry-After", "1");
            writeErrorResponse(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Maximum 1 request per second.", request.getRequestURI());
            return false;
        }

        request.setAttribute(AUTH_CUSTOMER_ID_ATTR, customerId);
        return true;
    }

    private void writeErrorResponse(HttpServletResponse response, HttpStatus status, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> errorBody = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "path", path
        );
        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }
}
