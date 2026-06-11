package com.example.ticketing.global.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker redisLockCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(this::isRedisInfraFailure)
                .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker("redisLock");
    }

    private boolean isRedisInfraFailure(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof RedisCommandTimeoutException ||
                cause instanceof RedisConnectionException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
