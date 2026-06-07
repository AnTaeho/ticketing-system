package com.example.ticketing.global.resilience;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class CircuitBreakerStatsHolder {

    private final AtomicLong redisPathCount    = new AtomicLong(0);
    private final AtomicLong fallbackPathCount = new AtomicLong(0);

    public void incrementRedisPath()    { redisPathCount.incrementAndGet(); }
    public void incrementFallbackPath() { fallbackPathCount.incrementAndGet(); }

    public void reset() {
        redisPathCount.set(0);
        fallbackPathCount.set(0);
    }

    public long getRedisPathCount()    { return redisPathCount.get(); }
    public long getFallbackPathCount() { return fallbackPathCount.get(); }
    public long getTotalCount()        { return redisPathCount.get() + fallbackPathCount.get(); }
}
