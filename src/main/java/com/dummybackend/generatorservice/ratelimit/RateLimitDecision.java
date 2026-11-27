package com.dummybackend.generatorservice.ratelimit;

public record RateLimitDecision(boolean allowed, Scope scope, long retryAfterSeconds) {

    public enum Scope {
        CLIENT,
        GLOBAL
    }

    static RateLimitDecision allow() {
        return new RateLimitDecision(true, null, 0);
    }

    static RateLimitDecision reject(Scope scope, long nanosToWait) {
        long seconds = (nanosToWait + 999_999_999L) / 1_000_000_000L;
        return new RateLimitDecision(false, scope, Math.max(1, seconds));
    }
}
