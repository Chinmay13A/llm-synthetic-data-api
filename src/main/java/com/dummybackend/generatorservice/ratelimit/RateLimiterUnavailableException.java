package com.dummybackend.generatorservice.ratelimit;

public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(Throwable cause) {
        super("Rate limiter backend is unavailable: " + cause.getMessage(), cause);
    }
}
