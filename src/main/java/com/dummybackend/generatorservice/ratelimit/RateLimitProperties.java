package com.dummybackend.generatorservice.ratelimit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Positive int requestsPerMinute,
        @Positive int requestsPerDay,
        @Positive int globalBurst,
        @Positive int globalRequestsPerDay,
        @NotBlank String redisUrl,
        @NotNull Duration redisTimeout
) {
}
