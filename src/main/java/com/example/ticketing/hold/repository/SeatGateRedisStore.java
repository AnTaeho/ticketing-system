package com.example.ticketing.hold.repository;

import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisScripts;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.GateResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 이미 잡힌 좌석 요청을 DB 앞에서 거절하는 게이트. 판정은 DB가 하고 여기 키는 빠른 거절용이다
// Redis 장애는 RedisUnavailableException으로 던진다. 장애 정책은 호출자와 SeatGateReflector가 정한다
@Component
public class SeatGateRedisStore {

    private final StringRedisTemplate redis;

    public SeatGateRedisStore(TicketingRedis ticketingRedis) {
        this.redis = ticketingRedis.template();
    }

    // seatIds는 오름차순이어야 한다
    public GateResult tryHold(GateHold hold, Duration ttl) {
        List<Long> seatIds = hold.seatIds();
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("좌석이 비었다");
        }
        long pid = hold.performanceId();
        int n = seatIds.size();
        List<String> keys = new ArrayList<>(2 * n + 1);
        keys.add(RedisKeys.userHold(hold.userId(), pid));
        seatIds.forEach(seatId -> keys.add(RedisKeys.seat(pid, seatId)));
        seatIds.forEach(seatId -> keys.add(RedisKeys.rehold(hold.userId(), pid, seatId)));
        List<?> reply = RedisFailures.translate(() -> redis.execute(RedisScripts.HOLD, keys, hold.holdToken(),
                String.valueOf(ttl.toMillis()), String.valueOf(n)));
        return GateResult.fromScript(reply);
    }

    // userhold 키와 좌석 키 중 값이 holdToken인 것만 지운다. 반환: 지운 키 수
    public long release(GateHold hold) {
        return RedisFailures.translate(
                () -> redis.execute(RedisScripts.RELEASE, userHoldAndSeatKeys(hold), hold.holdToken()));
    }

    // 예매 취소용. 확정 때 userhold는 이미 지웠으므로 좌석 키만 지운다
    public long releaseSeatsOnly(GateHold hold) {
        return RedisFailures.translate(
                () -> redis.execute(RedisScripts.RELEASE, seatKeys(hold), hold.holdToken()));
    }

    // 반환: 연장한 키 수
    public long extend(GateHold hold, Duration ttl) {
        return RedisFailures.translate(() -> redis.execute(RedisScripts.EXTEND, userHoldAndSeatKeys(hold),
                hold.holdToken(), String.valueOf(ttl.toMillis())));
    }

    // 좌석 키는 gateCloseAt에 만료되게 바꾸고 userhold는 지운다. 반환: 처리한 좌석 키 수
    public long confirm(GateHold hold, Instant gateCloseAt) {
        return RedisFailures.translate(() -> redis.execute(RedisScripts.CONFIRM, userHoldAndSeatKeys(hold),
                hold.holdToken(), String.valueOf(gateCloseAt.toEpochMilli())));
    }

    public void setReholdCooldown(GateHold hold, Duration cooldown) {
        RedisFailures.translate(() -> {
            for (long seatId : hold.seatIds()) {
                redis.opsForValue().set(RedisKeys.rehold(hold.userId(), hold.performanceId(), seatId), "1",
                        cooldown);
            }
        });
    }

    private static List<String> userHoldAndSeatKeys(GateHold hold) {
        List<String> keys = new ArrayList<>(hold.seatIds().size() + 1);
        keys.add(RedisKeys.userHold(hold.userId(), hold.performanceId()));
        keys.addAll(seatKeys(hold));
        return keys;
    }

    private static List<String> seatKeys(GateHold hold) {
        return hold.seatIds().stream().map(seatId -> RedisKeys.seat(hold.performanceId(), seatId)).toList();
    }
}
