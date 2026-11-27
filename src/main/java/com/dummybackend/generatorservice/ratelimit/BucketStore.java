package com.dummybackend.generatorservice.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;

import java.util.function.Supplier;

/**
 * Where token buckets live. The configuration is only used when the bucket does not exist yet.
 */
public interface BucketStore {

    Bucket resolve(String key, Supplier<BucketConfiguration> configuration);
}
