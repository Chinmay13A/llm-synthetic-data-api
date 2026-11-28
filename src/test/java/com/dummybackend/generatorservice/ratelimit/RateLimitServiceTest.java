package com.dummybackend.generatorservice.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceTest {

    private final InMemoryBucketStore store = new InMemoryBucketStore();

    private RateLimitService service(int perClient, int globalBurst, int globalPerDay) {
        return service(perClient, 10_000, globalBurst, globalPerDay);
    }

    private RateLimitService service(int perClientMinute, int perClientDay, int globalBurst, int globalPerDay) {
        return new RateLimitService(
                new RateLimitProperties(true, perClientMinute, perClientDay, globalBurst, globalPerDay,
                        "redis://localhost:6379", Duration.ofSeconds(2)),
                store);
    }

    @Test
    void rejectsClientOverItsOwnLimit() {
        RateLimitService service = service(2, 50, 100);

        assertThat(service.tryConsume("1.1.1.1").allowed()).isTrue();
        assertThat(service.tryConsume("1.1.1.1").allowed()).isTrue();

        RateLimitDecision third = service.tryConsume("1.1.1.1");
        assertThat(third.allowed()).isFalse();
        assertThat(third.scope()).isEqualTo(RateLimitDecision.Scope.CLIENT);
        assertThat(third.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void clientsHaveIndependentLimits() {
        RateLimitService service = service(1, 50, 100);

        assertThat(service.tryConsume("1.1.1.1").allowed()).isTrue();
        assertThat(service.tryConsume("2.2.2.2").allowed()).isTrue();
        assertThat(store.contains(RateLimitService.CLIENT_KEY_PREFIX + "1.1.1.1")).isTrue();
    }

    @Test
    void clientDailyLimitStopsAnIpPacingItselfUnderTheMinuteLimit() {
        RateLimitService service = service(5, 8, 100, 100);

        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsume("1.1.1.1").allowed()).isTrue();
        }
        store.advance(Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            assertThat(service.tryConsume("1.1.1.1").allowed()).isTrue();
        }

        // The minute limit has room again, but the IP has used its 8 for the day.
        RateLimitDecision ninth = service.tryConsume("1.1.1.1");
        assertThat(ninth.allowed()).isFalse();
        assertThat(ninth.scope()).isEqualTo(RateLimitDecision.Scope.CLIENT);
        assertThat(ninth.retryAfterSeconds()).isGreaterThan(Duration.ofHours(2).toSeconds());

        assertThat(service.tryConsume("2.2.2.2").allowed()).isTrue();
    }

    @Test
    void oneIpCannotDrainTheGlobalBurst() {
        RateLimitService service = service(5, 5, 20, 200);
        int allowedForOneIp = 0;

        // One IP keeps trying every minute for an hour.
        for (int minute = 0; minute < 60; minute++) {
            while (service.tryConsume("1.1.1.1").allowed()) {
                allowedForOneIp++;
            }
            store.advance(Duration.ofMinutes(1));
        }

        assertThat(allowedForOneIp).isBetween(5, 6);
        assertThat(service.tryConsume("2.2.2.2").allowed()).isTrue();
    }

    @Test
    void globalBurstLimitAppliesAcrossClients() {
        RateLimitService service = service(5, 3, 100);

        assertThat(service.tryConsume("1.1.1.1").allowed()).isTrue();
        assertThat(service.tryConsume("2.2.2.2").allowed()).isTrue();
        assertThat(service.tryConsume("3.3.3.3").allowed()).isTrue();

        RateLimitDecision fourth = service.tryConsume("4.4.4.4");
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.scope()).isEqualTo(RateLimitDecision.Scope.GLOBAL);
    }

    @Test
    void globalDailyCapCannotBeBypassedByRotatingIps() {
        RateLimitService service = service(5, 3, 3);

        for (int i = 0; i < 3; i++) {
            assertThat(service.tryConsume("10.0.0." + i).allowed()).isTrue();
        }

        RateLimitDecision rejected = service.tryConsume("10.0.0.99");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.scope()).isEqualTo(RateLimitDecision.Scope.GLOBAL);
        // Gradual refill: 3 tokens per day means waiting about 8 hours for the next one.
        assertThat(rejected.retryAfterSeconds()).isGreaterThan(Duration.ofHours(7).toSeconds());
    }

    @Test
    void clientOverItsLimitDoesNotConsumeGlobalQuota() {
        RateLimitService service = service(1, 2, 2);

        assertThat(service.tryConsume("1.1.1.1").allowed()).isTrue();
        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsume("1.1.1.1").scope()).isEqualTo(RateLimitDecision.Scope.CLIENT);
        }

        assertThat(service.tryConsume("2.2.2.2").allowed()).isTrue();
    }

    @Test
    void anyTwentyFourHoursAllowAtMostBurstPlusDailyLimit() {
        RateLimitService service = service(5, 20, 200);
        int allowed = 0;
        int ip = 0;

        // Hammer the API from a new IP every time, checking once a minute for a full day.
        for (int minute = 0; minute <= 24 * 60; minute++) {
            while (service.tryConsume("10.0." + (ip / 256) + "." + (ip++ % 256)).allowed()) {
                allowed++;
            }
            store.advance(Duration.ofMinutes(1));
        }

        assertThat(allowed).isBetween(219, 220);
    }

    @Test
    void rejectsBurstLargerThanDailyLimit() {
        assertThatThrownBy(() -> service(5, 300, 200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("global-burst");
    }

    @Test
    void storeFailureIsReportedAsUnavailable() {
        BucketStore broken = (key, configuration) -> {
            throw new IllegalStateException("connection refused");
        };
        RateLimitService service = new RateLimitService(
                new RateLimitProperties(true, 5, 20, 20, 200, "redis://localhost:6379", Duration.ofSeconds(2)),
                broken);

        assertThatThrownBy(() -> service.tryConsume("1.1.1.1"))
                .isInstanceOf(RateLimiterUnavailableException.class)
                .hasMessageContaining("connection refused");
    }
}
