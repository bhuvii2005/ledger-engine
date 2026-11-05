package com.bhuvan.ledger.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
public class RedisConfig {

    /**
     * Redis is optional for correctness, so the app reports it but never goes DOWN because of it.
     * The root health status ignores DEGRADED (see management.endpoint.health.status.order).
     */
    public static final Status DEGRADED = new Status("DEGRADED",
            "Redis unavailable; idempotency checks fall back to Postgres");

    /**
     * Fail fast when Redis is gone. By default Lettuce queues commands while it reconnects, so
     * every request would wait out the full timeout; with REJECT_COMMANDS they fail at once and
     * IdempotencyCache treats that as a miss.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastLettuce(
            @Value("${spring.data.redis.timeout}") Duration timeout) {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .build());
    }

    @Bean(name = "redisHealthIndicator")
    HealthIndicator redisHealthIndicator(RedisConnectionFactory connectionFactory) {
        return () -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                return Health.up().withDetail("ping", connection.ping()).build();
            } catch (RuntimeException e) {
                return Health.status(DEGRADED).withDetail("error", e.getClass().getSimpleName()).build();
            }
        };
    }
}
