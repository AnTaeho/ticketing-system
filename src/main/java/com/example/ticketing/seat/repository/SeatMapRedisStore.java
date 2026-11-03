package com.example.ticketing.seat.repository;

import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisScripts;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 스냅샷·매진 표시·리스의 Redis 읽기·쓰기만 맡는다. Redis 장애는 RedisUnavailableException으로 던진다
@Component
public class SeatMapRedisStore {

    // 스케줄러가 더 만들지 않는 회차(예매가 끝난 회차)의 스냅샷과 매진 표시가 남지 않게 한다
    public static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);

    private static final String SOLD_OUT = "1";
    private static final String NOT_SOLD_OUT = "0";

    private final StringRedisTemplate redis;

    public SeatMapRedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 없으면 null
    public String get(long performanceId) {
        return RedisFailures.translate(() -> redis.opsForValue().get(RedisKeys.seatMap(performanceId)));
    }

    public void put(long performanceId, String json) {
        RedisFailures.translate(() -> redis.opsForValue().set(RedisKeys.seatMap(performanceId), json, SNAPSHOT_TTL));
    }

    public void putSoldOut(long performanceId, boolean soldOut) {
        RedisFailures.translate(() -> redis.opsForValue()
                .set(RedisKeys.seatMapSoldOut(performanceId), soldOut ? SOLD_OUT : NOT_SOLD_OUT, SNAPSHOT_TTL));
    }

    // 표시가 없으면(스냅샷을 아직 안 만들었거나 TTL이 지났으면) 매진이 아니다
    public boolean isSoldOut(long performanceId) {
        return SOLD_OUT.equals(
                RedisFailures.translate(() -> redis.opsForValue().get(RedisKeys.seatMapSoldOut(performanceId))));
    }

    // 비어 있거나 이미 owner 것이면 true(TTL 갱신)
    public boolean tryLease(long performanceId, String owner, Duration ttl) {
        Long acquired = RedisFailures.translate(() -> redis.execute(RedisScripts.SEATMAP_LEASE,
                List.of(RedisKeys.seatMapLease(performanceId)), owner, String.valueOf(ttl.toMillis())));
        return acquired != null && acquired == 1L;
    }
}
