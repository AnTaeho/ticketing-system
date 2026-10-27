package com.example.ticketing.booking.hold.gate;

import com.example.ticketing.booking.common.BookingRedis;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// 이미 잡힌 좌석 요청을 DB 앞에서 거절하는 게이트. 판정은 DB가 하고 여기 키는 빠른 거절용이다
@Slf4j
@Component
public class RedisSeatGate {

    private static final String REFLECT_FAILURE = "booking.redis.reflect.failure";

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> HOLD = script("hold.lua", List.class);
    private static final DefaultRedisScript<Long> RELEASE = script("release.lua", Long.class);
    private static final DefaultRedisScript<Long> EXTEND = script("extend.lua", Long.class);
    private static final DefaultRedisScript<Long> CONFIRM = script("confirm.lua", Long.class);

    private final StringRedisTemplate redis;
    private final Counter releaseFailure;
    private final Counter extendFailure;
    private final Counter confirmFailure;
    private final Counter cooldownFailure;

    public RedisSeatGate(BookingRedis bookingRedis, MeterRegistry meterRegistry) {
        this.redis = bookingRedis.template();
        this.releaseFailure = meterRegistry.counter(REFLECT_FAILURE, "op", "release");
        this.extendFailure = meterRegistry.counter(REFLECT_FAILURE, "op", "extend");
        this.confirmFailure = meterRegistry.counter(REFLECT_FAILURE, "op", "confirm");
        this.cooldownFailure = meterRegistry.counter(REFLECT_FAILURE, "op", "cooldown");
    }

    // Redis 예외를 그대로 던진다. 장애 시 DB 판정으로 넘기는 일은 호출자가 한다
    public GateResult tryHold(long performanceId, long userId, List<Long> sortedSeatIds,
                              String holdToken, Duration ttl) {
        if (sortedSeatIds.isEmpty()) {
            throw new IllegalArgumentException("좌석이 비었다");
        }
        int n = sortedSeatIds.size();
        List<String> keys = new ArrayList<>(2 * n + 1);
        keys.add(BookingKeys.userHold(performanceId, userId));
        sortedSeatIds.forEach(seatId -> keys.add(BookingKeys.seat(performanceId, seatId)));
        sortedSeatIds.forEach(seatId -> keys.add(BookingKeys.rehold(performanceId, userId, seatId)));
        List<?> reply = redis.execute(HOLD, keys, holdToken, String.valueOf(ttl.toMillis()), String.valueOf(n));
        return GateResult.fromScript(reply);
    }

    // userhold 키와 좌석 키 중 값이 holdToken인 것만 지운다. 반환: 지운 키 수
    public long release(long performanceId, long userId, List<Long> seatIds, String holdToken) {
        return redis.execute(RELEASE, userHoldAndSeatKeys(performanceId, userId, seatIds), holdToken);
    }

    // 예매 취소용. 확정 때 userhold는 이미 지웠으므로 좌석 키만 지운다
    public long releaseSeatsOnly(long performanceId, List<Long> seatIds, String holdToken) {
        return redis.execute(RELEASE, seatKeys(performanceId, seatIds), holdToken);
    }

    // 반환: 연장한 키 수
    public long extend(long performanceId, long userId, List<Long> seatIds, String holdToken, Duration ttl) {
        return redis.execute(EXTEND, userHoldAndSeatKeys(performanceId, userId, seatIds),
                holdToken, String.valueOf(ttl.toMillis()));
    }

    // 좌석 키는 gateCloseAt에 만료되게 바꾸고 userhold는 지운다. 반환: 처리한 좌석 키 수
    public long confirm(long performanceId, long userId, List<Long> seatIds, String holdToken, Instant gateCloseAt) {
        return redis.execute(CONFIRM, userHoldAndSeatKeys(performanceId, userId, seatIds),
                holdToken, String.valueOf(gateCloseAt.toEpochMilli()));
    }

    public void setReholdCooldown(long performanceId, long userId, List<Long> seatIds, Duration cooldown) {
        for (long seatId : seatIds) {
            redis.opsForValue().set(BookingKeys.rehold(performanceId, userId, seatId), "1", cooldown);
        }
    }

    // 아래 Quietly 변형은 DB 커밋 뒤 반영용이다. 실패해도 사용자 응답을 바꾸지 않고 고아 키 대사가 복구한다
    public void releaseQuietly(long performanceId, long userId, List<Long> seatIds, String holdToken) {
        quietly("release", releaseFailure, performanceId,
                () -> release(performanceId, userId, seatIds, holdToken));
    }

    public void releaseSeatsOnlyQuietly(long performanceId, List<Long> seatIds, String holdToken) {
        quietly("release", releaseFailure, performanceId,
                () -> releaseSeatsOnly(performanceId, seatIds, holdToken));
    }

    public void extendQuietly(long performanceId, long userId, List<Long> seatIds, String holdToken, Duration ttl) {
        quietly("extend", extendFailure, performanceId,
                () -> extend(performanceId, userId, seatIds, holdToken, ttl));
    }

    public void confirmQuietly(long performanceId, long userId, List<Long> seatIds, String holdToken,
                               Instant gateCloseAt) {
        quietly("confirm", confirmFailure, performanceId,
                () -> confirm(performanceId, userId, seatIds, holdToken, gateCloseAt));
    }

    public void setReholdCooldownQuietly(long performanceId, long userId, List<Long> seatIds, Duration cooldown) {
        quietly("cooldown", cooldownFailure, performanceId,
                () -> setReholdCooldown(performanceId, userId, seatIds, cooldown));
    }

    private void quietly(String op, Counter failure, long performanceId, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException | RedisException e) {
            failure.increment();
            log.warn("[booking gate] Redis 반영 실패 op={}, performanceId={}: {}", op, performanceId, e.toString());
        }
    }

    private static List<String> userHoldAndSeatKeys(long performanceId, long userId, List<Long> seatIds) {
        List<String> keys = new ArrayList<>(seatIds.size() + 1);
        keys.add(BookingKeys.userHold(performanceId, userId));
        keys.addAll(seatKeys(performanceId, seatIds));
        return keys;
    }

    private static List<String> seatKeys(long performanceId, List<Long> seatIds) {
        return seatIds.stream().map(seatId -> BookingKeys.seat(performanceId, seatId)).toList();
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/booking/" + name));
        script.setResultType(resultType);
        return script;
    }
}
