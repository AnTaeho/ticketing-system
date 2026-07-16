package com.example.ticketing.global.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LettuceLockRepository {

    private static final String LOCK_KEY_PREFIX = "lock:concert:";

    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public String tryLock(Long concertId) {
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(buildKey(concertId), lockValue, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? lockValue : null;
    }

    public void releaseLock(Long concertId, String lockValue) {
        redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(buildKey(concertId)), lockValue);
    }

    private String buildKey(Long concertId) {
        return LOCK_KEY_PREFIX + concertId;
    }
}
