package com.example.ticketing.ops.repository;

import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 대기열·게이트 키를 세기만 한다. Redis 장애는 RedisUnavailableException으로 던진다
@Component
public class OpsRedisStore {

    private static final long SCAN_COUNT = 200;

    private final StringRedisTemplate redis;

    public OpsRedisStore(TicketingRedis ticketingRedis) {
        this.redis = ticketingRedis.template();
    }

    public long waitingCount(long performanceId) {
        return RedisFailures.translate(() -> orZero(redis.opsForZSet().zCard(RedisKeys.queueWaiting(performanceId))));
    }

    public long processingCount(long performanceId) {
        return RedisFailures.translate(
                () -> orZero(redis.opsForZSet().zCard(RedisKeys.queueProcessing(performanceId))));
    }

    public long seatKeyCount(long performanceId) {
        return countKeys(RedisKeys.seatPattern(performanceId));
    }

    public long userHoldKeyCount(long performanceId) {
        return countKeys(RedisKeys.userHoldPattern(performanceId));
    }

    // 회차 하나의 키만 훑는다. 데모 규모라 SCAN으로 세도 충분하다
    private long countKeys(String pattern) {
        return RedisFailures.translate(() -> {
            long count = 0;
            try (Cursor<String> cursor = redis.scan(
                    ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build())) {
                while (cursor.hasNext()) {
                    cursor.next();
                    count++;
                }
            }
            return count;
        });
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
