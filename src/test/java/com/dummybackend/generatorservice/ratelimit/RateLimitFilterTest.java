package com.dummybackend.generatorservice.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static final RateLimitProperties PROPERTIES =
            new RateLimitProperties(true, 1, 100, 100, 100, "redis://localhost:6379", Duration.ofSeconds(2));

    private final RateLimitFilter filter = filterWith(new InMemoryBucketStore());

    private static RateLimitFilter filterWith(BucketStore store) {
        return new RateLimitFilter(PROPERTIES, new RateLimitService(PROPERTIES, store), JsonMapper.builder().build());
    }

    private static MockHttpServletRequest generateRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/generate");
        request.setServletPath("/generate");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static MockHttpServletResponse send(RateLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void returns429WithRetryAfterWhenClientLimitIsExceeded() throws Exception {
        assertThat(send(filter, generateRequest("1.1.1.1")).getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = send(filter, generateRequest("1.1.1.1"));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(limited.getContentAsString()).contains("Rate limit exceeded", "per IP");
    }

    @Test
    void spoofedForwardedForHeaderDoesNotResetTheLimit() throws Exception {
        send(filter, generateRequest("1.1.1.1"));

        MockHttpServletRequest spoofed = generateRequest("1.1.1.1");
        spoofed.addHeader("X-Forwarded-For", "9.9.9.9");

        assertThat(send(filter, spoofed).getStatus()).isEqualTo(429);
    }

    @Test
    void failsClosedWith503WhenStoreIsUnavailable() throws Exception {
        RateLimitFilter broken = filterWith((key, configuration) -> {
            throw new IllegalStateException("connection refused");
        });

        MockHttpServletResponse response = send(broken, generateRequest("1.1.1.1"));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Rate limiter unavailable");
    }

    @Test
    void healthEndpointIsNotRateLimited() throws Exception {
        RateLimitFilter broken = filterWith((key, configuration) -> {
            throw new IllegalStateException("connection refused");
        });
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/health");
        health.setServletPath("/health");

        assertThat(send(broken, health).getStatus()).isEqualTo(200);
    }
}
