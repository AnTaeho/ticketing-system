package com.example.ticketing.global.lock;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LettuceLockRepository {

    private static final String LOCK_KEY_PREFIX = "lock:concert:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(Long concertId) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue()
                        .setIfAbsent(buildKey(concertId), "locked", LOCK_TTL)
        );
    }

    public void releaseLock(Long concertId) {
        redisTemplate.delete(buildKey(concertId));
    }

    private String buildKey(Long concertId) {
        return LOCK_KEY_PREFIX + concertId;
    }
}
