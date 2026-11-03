package com.example.ticketing.hold.service;

import static com.example.ticketing.common.metrics.Metrics.HoldResult.COOLDOWN;
import static com.example.ticketing.common.metrics.Metrics.HoldResult.ERROR;
import static com.example.ticketing.common.metrics.Metrics.HoldResult.OK;
import static com.example.ticketing.common.metrics.Metrics.HoldResult.SEAT_TAKEN;
import static com.example.ticketing.common.metrics.Metrics.HoldResult.SKIPPED;
import static com.example.ticketing.common.metrics.Metrics.HoldResult.STATE_CHANGED;
import static com.example.ticketing.common.metrics.Metrics.HoldResult.USER_HAS_HOLD;

import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.FallbackResult;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.hold.domain.GateResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

// 선점 흐름의 카운터. 게이트·DB 판정·폴백·만료를 결과별로 센다
@Component
public class HoldMetrics {

    private final Map<String, Counter> db;
    private final Map<String, Counter> gate;
    private final Map<String, Counter> fallback;
    private final Counter expired;

    public HoldMetrics(MeterRegistry meterRegistry) {
        this.db = Metrics.counters(meterRegistry, Metrics.HOLD_DB, Tag.RESULT, OK, STATE_CHANGED, USER_HAS_HOLD);
        this.gate = Metrics.counters(meterRegistry, Metrics.HOLD_GATE, Tag.RESULT,
                OK, SEAT_TAKEN, USER_HAS_HOLD, COOLDOWN, SKIPPED, ERROR);
        this.fallback = Metrics.counters(meterRegistry, Metrics.HOLD_FALLBACK, Tag.RESULT,
                FallbackResult.ACQUIRED, FallbackResult.REJECTED);
        this.expired = meterRegistry.counter(Metrics.HOLD_EXPIRED);
    }

    public void dbAccepted() {
        db.get(OK).increment();
    }

    // 좌석 상태 변경과 1인 1선점 위반만 센다
    public void dbRejected(ErrorCode code) {
        if (code == ErrorCode.SEAT_STATE_CHANGED) {
            db.get(STATE_CHANGED).increment();
        } else if (code == ErrorCode.USER_HAS_HOLD) {
            db.get(USER_HAS_HOLD).increment();
        }
    }

    public void gate(GateResult.Type type) {
        gate.get(switch (type) {
            case OK -> OK;
            case USER_HAS_HOLD -> USER_HAS_HOLD;
            case SEAT_TAKEN -> SEAT_TAKEN;
            case COOLDOWN -> COOLDOWN;
        }).increment();
    }

    public void gateSkipped() {
        gate.get(SKIPPED).increment();
    }

    public void gateError() {
        gate.get(ERROR).increment();
    }

    public void fallbackAcquired() {
        fallback.get(FallbackResult.ACQUIRED).increment();
    }

    public void fallbackRejected() {
        fallback.get(FallbackResult.REJECTED).increment();
    }

    public void expired(int count) {
        expired.increment(count);
    }
}
