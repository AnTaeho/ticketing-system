package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.ReflectOp;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// DB 커밋 뒤 게이트 키 반영. 게이트가 꺼져 있으면 Redis를 부르지 않는다
// 실패해도 사용자 응답을 바꾸지 않는다. 남은 키는 TTL과 고아 키 대사가 치운다
@Slf4j
@Component
public class SeatGateReflector {

    private final SeatGateRedisStore store;
    private final boolean enabled;
    private final Duration reholdCooldown;
    private final Counter releaseFailure;
    private final Counter extendFailure;
    private final Counter confirmFailure;
    private final Counter cooldownFailure;
    private final ThrottledWarn failureWarn;

    public SeatGateReflector(SeatGateRedisStore store, TicketingProperties properties, MeterRegistry meterRegistry,
                             LongSupplier nanoClock) {
        this.store = store;
        this.enabled = properties.gate().enabled();
        this.reholdCooldown = properties.hold().reholdCooldown();
        this.releaseFailure = failureCounter(meterRegistry, ReflectOp.RELEASE);
        this.extendFailure = failureCounter(meterRegistry, ReflectOp.EXTEND);
        this.confirmFailure = failureCounter(meterRegistry, ReflectOp.CONFIRM);
        this.cooldownFailure = failureCounter(meterRegistry, ReflectOp.COOLDOWN);
        this.failureWarn = new ThrottledWarn(log, nanoClock);
    }

    // 게이트 스위치(ticketing.gate.enabled)는 여기서만 읽는다
    public boolean enabled() {
        return enabled;
    }

    public void release(GateHold hold) {
        reflect(ReflectOp.RELEASE, releaseFailure, hold, () -> store.release(hold));
    }

    // 예매 취소용. 확정 때 userhold는 이미 지웠으므로 좌석 키만 지운다
    public void releaseSeatsOnly(GateHold hold) {
        reflect(ReflectOp.RELEASE, releaseFailure, hold, () -> store.releaseSeatsOnly(hold));
    }

    public void extend(GateHold hold, Duration ttl) {
        reflect(ReflectOp.EXTEND, extendFailure, hold, () -> store.extend(hold, ttl));
    }

    public void confirm(GateHold hold, Instant gateCloseAt) {
        reflect(ReflectOp.CONFIRM, confirmFailure, hold, () -> store.confirm(hold, gateCloseAt));
    }

    // 쿨다운을 먼저 걸어 키가 풀린 틈에 같은 사용자가 다시 잡지 못하게 한다. 쥔 좌석이 없으면 쿨다운도 없다
    public void expire(GateHold hold) {
        if (!hold.seatIds().isEmpty()) {
            reflect(ReflectOp.COOLDOWN, cooldownFailure, hold, () -> store.setReholdCooldown(hold, reholdCooldown));
        }
        release(hold);
    }

    private void reflect(String op, Counter failure, GateHold hold, Runnable call) {
        if (!enabled) {
            return;
        }
        try {
            call.run();
        } catch (RedisUnavailableException e) {
            failure.increment();
            failureWarn.warn("[gate] Redis 반영 실패 op={} performanceId={} cause={}", op, hold.performanceId(),
                    RedisFailures.describe(e));
        }
    }

    private static Counter failureCounter(MeterRegistry meterRegistry, String op) {
        return meterRegistry.counter(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, op);
    }
}
