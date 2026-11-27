package com.dummybackend.generatorservice.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {

    static final String GLOBAL_KEY = "rl:global";
    static final String CLIENT_KEY_PREFIX = "rl:ip:";

    private final RateLimitProperties properties;
    private final BucketStore bucketStore;
    private final BucketConfiguration clientConfig;
    private final BucketConfiguration globalConfig;

    public RateLimitService(RateLimitProperties properties, BucketStore bucketStore) {
        this.properties = properties;
        this.bucketStore = bucketStore;

        // Per-minute limit stops bursts; per-day limit stops one IP pacing itself to drain the global quota.
        int clientPerMinute = properties.requestsPerMinute();
        int clientPerDay = properties.requestsPerDay();
        this.clientConfig = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(clientPerMinute).refillGreedy(clientPerMinute, Duration.ofMinutes(1)))
                .addLimit(limit -> limit.capacity(clientPerDay).refillGreedy(clientPerDay, Duration.ofDays(1)))
                .build();

        // Bounds total LLM spend. Any 24 hours allows at most burst + perDay requests,
        // so a small burst keeps the daily cap close to its configured value.
        int perDay = properties.globalRequestsPerDay();
        int burst = properties.globalBurst();
        if (burst > perDay) {
            throw new IllegalStateException("rate-limit.global-burst (" + burst
                    + ") must not exceed rate-limit.global-requests-per-day (" + perDay + ")");
        }
        this.globalConfig = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(burst).refillGreedy(perDay, Duration.ofDays(1)))
                .build();
    }

    /**
     * Checks the client's own limit first, so a client that is already over it
     * does not use up the shared global quota.
     *
     * @throws RateLimiterUnavailableException if the bucket store cannot be reached
     */
    public RateLimitDecision tryConsume(String clientIp) {
        try {
            ConsumptionProbe client = bucketStore.resolve(CLIENT_KEY_PREFIX + clientIp, () -> clientConfig)
                    .tryConsumeAndReturnRemaining(1);
            if (!client.isConsumed()) {
                return RateLimitDecision.reject(RateLimitDecision.Scope.CLIENT, client.getNanosToWaitForRefill());
            }

            ConsumptionProbe global = bucketStore.resolve(GLOBAL_KEY, () -> globalConfig)
                    .tryConsumeAndReturnRemaining(1);
            if (!global.isConsumed()) {
                return RateLimitDecision.reject(RateLimitDecision.Scope.GLOBAL, global.getNanosToWaitForRefill());
            }

            return RateLimitDecision.allow();
        } catch (RuntimeException e) {
            throw new RateLimiterUnavailableException(e);
        }
    }

    public int requestsPerMinute() {
        return properties.requestsPerMinute();
    }

    public int requestsPerDay() {
        return properties.requestsPerDay();
    }
}
