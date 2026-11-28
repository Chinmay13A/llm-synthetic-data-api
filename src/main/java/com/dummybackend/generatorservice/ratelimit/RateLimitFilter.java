package com.dummybackend.generatorservice.ratelimit;

import com.dummybackend.generatorservice.exception.ErrorResponse;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RateLimitProperties properties,
            RateLimitService rateLimitService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/generate".equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        // Remote address of the actual connection; client-supplied X-Forwarded-For is not trusted.
        String clientIp = request.getRemoteAddr();

        RateLimitDecision decision;
        try {
            decision = rateLimitService.tryConsume(clientIp);
        } catch (RateLimiterUnavailableException e) {
            // Fail closed: without the limiter, LLM spend would be unbounded.
            log.error("Rejecting request because the rate limiter is unavailable", e);
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, new ErrorResponse(
                    "Rate limiter unavailable",
                    List.of("Requests are temporarily rejected because the rate limiter cannot be reached.")));
            return;
        }

        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        writeError(response, HttpStatus.TOO_MANY_REQUESTS, rateLimitExceededBody(decision.scope()));
    }

    private ErrorResponse rateLimitExceededBody(RateLimitDecision.Scope scope) {
        String detail = switch (scope) {
            case CLIENT -> "Too many requests. Limits are " + rateLimitService.requestsPerMinute()
                    + " requests per minute and " + rateLimitService.requestsPerDay() + " requests per day per IP.";
            case GLOBAL -> "The service has reached its overall request limit. Try again later.";
        };
        return new ErrorResponse("Rate limit exceeded", List.of(detail));
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorResponse body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
