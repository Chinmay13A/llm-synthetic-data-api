package com.dummybackend.generatorservice.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.local.LocalBucketBuilder;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

class InMemoryBucketStore implements BucketStore {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ManualClock clock = new ManualClock();

    @Override
    public Bucket resolve(String key, Supplier<BucketConfiguration> configuration) {
        return buckets.computeIfAbsent(key, k -> {
            LocalBucketBuilder builder = Bucket.builder().withCustomTimePrecision(clock);
            for (Bandwidth bandwidth : configuration.get().getBandwidths()) {
                builder.addLimit(bandwidth);
            }
            return builder.build();
        });
    }

    boolean contains(String key) {
        return buckets.containsKey(key);
    }

    void advance(Duration duration) {
        clock.nanos += duration.toNanos();
    }

    private static class ManualClock implements TimeMeter {

        private volatile long nanos;

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }
}
