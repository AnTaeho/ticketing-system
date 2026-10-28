package com.example.ticketing.seat.repository;

import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisScripts;
import com.example.ticketing.common.redis.TicketingRedis;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 스냅샷·리스의 Redis 읽기·쓰기만 맡는다. Redis 예외는 호출자가 장애 분기를 고르도록 그대로 던진다
@Component
public class SeatMapRedisStore {

    private final StringRedisTemplate redis;

    public SeatMapRedisStore(TicketingRedis bookingRedis) {
        this.redis = bookingRedis.template();
    }

    public String get(long performanceId) {
        return redis.opsForValue().get(RedisKeys.seatMap(performanceId));
    }

    public void put(long performanceId, String json, Duration ttl) {
        redis.opsForValue().set(RedisKeys.seatMap(performanceId), json, ttl);
    }

    // 비어 있거나 이미 owner 것이면 true(TTL 갱신)
    public boolean tryLease(long performanceId, String owner, Duration ttl) {
        Long acquired = redis.execute(RedisScripts.SEATMAP_LEASE, List.of(RedisKeys.seatMapLease(performanceId)),
                owner, String.valueOf(ttl.toMillis()));
        return acquired != null && acquired == 1L;
    }

    // 값이 owner일 때만 지운다. TTL이 지나 남이 잡은 리스는 건드리지 않는다
    public void releaseLease(long performanceId, String owner) {
        redis.execute(RedisScripts.RELEASE, List.of(RedisKeys.seatMapLease(performanceId)), owner);
    }
}
