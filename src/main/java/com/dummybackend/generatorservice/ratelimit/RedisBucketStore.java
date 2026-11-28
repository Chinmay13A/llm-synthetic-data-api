package com.dummybackend.generatorservice.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Buckets stored in Redis, so limits are shared across instances and survive restarts.
 * Connects lazily on first use, so the app still starts while Redis is down.
 */
@Component
public class RedisBucketStore implements BucketStore, DisposableBean {

    // Keep a bucket key a little longer than it takes to refill completely, then let Redis delete it.
    private static final Duration KEEP_AFTER_FULL = Duration.ofSeconds(10);

    private final RedisClient redisClient;
    private final Duration timeout;

    private volatile ProxyManager<String> proxyManager;
    private StatefulRedisConnection<String, byte[]> connection;

    public RedisBucketStore(RateLimitProperties properties) {
        this.timeout = properties.redisTimeout();

        RedisURI uri = RedisURI.create(properties.redisUrl());
        uri.setTimeout(timeout);

        this.redisClient = RedisClient.create(uri);
        // Fail fast while Redis is unreachable instead of queueing commands until the timeout.
        this.redisClient.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .build());
    }

    @Override
    public Bucket resolve(String key, Supplier<BucketConfiguration> configuration) {
        return proxyManager().builder().build(key, configuration);
    }

    private ProxyManager<String> proxyManager() {
        ProxyManager<String> current = proxyManager;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (proxyManager == null) {
                connection = redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                        .expirationAfterWrite(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL))
                        .requestTimeout(timeout)
                        .build();
            }
            return proxyManager;
        }
    }

    @Override
    public void destroy() {
        if (connection != null) {
            connection.close();
        }
        redisClient.shutdown();
    }
}
