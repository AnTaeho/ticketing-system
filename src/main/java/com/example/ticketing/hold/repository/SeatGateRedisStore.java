package com.example.ticketing.hold.repository;

import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisScripts;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.GateResult;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 이미 잡힌 좌석 요청을 DB 앞에서 거절하는 게이트. 판정은 DB가 하고 여기 키는 빠른 거절용이다
// Redis 장애는 RedisUnavailableException으로 던진다. 장애 정책은 호출자와 SeatGateReflector가 정한다
@Component
public class SeatGateRedisStore {

    private final StringRedisTemplate redis;

    public SeatGateRedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // seatIds는 오름차순이어야 한다
    public GateResult tryHold(GateHold hold, Duration ttl) {
        if (hold.seatIds().isEmpty()) {
            throw new IllegalArgumentException("좌석이 비었다");
        }
        List<?> reply = RedisFailures.translate(() -> redis.execute(RedisScripts.HOLD, seatKeys(hold),
                hold.holdToken(), String.valueOf(ttl.toMillis())));
        return GateResult.fromScript(reply);
    }

    // 값이 holdToken인 좌석 키만 지운다. 그냥 DEL하면 그새 남이 잡은 키를 지운다. 반환: 지운 키 수
    public long release(GateHold hold) {
        Long removed = RedisFailures.translate(
                () -> redis.execute(RedisScripts.DELETE_IF_EQUALS, seatKeys(hold), hold.holdToken()));
        return removed == null ? 0 : removed;
    }

    private static List<String> seatKeys(GateHold hold) {
        return hold.seatIds().stream().map(seatId -> RedisKeys.seat(hold.performanceId(), seatId)).toList();
    }
}
