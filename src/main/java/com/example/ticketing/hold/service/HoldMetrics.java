package com.example.ticketing.hold.service;

import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.FallbackResult;
import com.example.ticketing.common.metrics.Metrics.HoldResult;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.hold.domain.GateResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// 선점 흐름의 카운터. 게이트·DB 판정·폴백·만료를 결과별로 센다
@Component
public class HoldMetrics {

    private final Counter dbOk;
    private final Counter dbStateChanged;
    private final Counter dbUserHasHold;
    private final Counter gateOk;
    private final Counter gateSeatTaken;
    private final Counter gateUserHasHold;
    private final Counter gateCooldown;
    private final Counter gateSkipped;
    private final Counter gateError;
    private final Counter fallbackAcquired;
    private final Counter fallbackRejected;
    private final Counter expired;

    public HoldMetrics(MeterRegistry meterRegistry) {
        this.dbOk = meterRegistry.counter(Metrics.HOLD_DB, Tag.RESULT, HoldResult.OK);
        this.dbStateChanged = meterRegistry.counter(Metrics.HOLD_DB, Tag.RESULT, HoldResult.STATE_CHANGED);
        this.dbUserHasHold = meterRegistry.counter(Metrics.HOLD_DB, Tag.RESULT, HoldResult.USER_HAS_HOLD);
        this.gateOk = meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, HoldResult.OK);
        this.gateSeatTaken = meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, HoldResult.SEAT_TAKEN);
        this.gateUserHasHold = meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, HoldResult.USER_HAS_HOLD);
        this.gateCooldown = meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, HoldResult.COOLDOWN);
        this.gateSkipped = meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, HoldResult.SKIPPED);
        this.gateError = meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, HoldResult.ERROR);
        this.fallbackAcquired = meterRegistry.counter(Metrics.HOLD_FALLBACK, Tag.RESULT, FallbackResult.ACQUIRED);
        this.fallbackRejected = meterRegistry.counter(Metrics.HOLD_FALLBACK, Tag.RESULT, FallbackResult.REJECTED);
        this.expired = meterRegistry.counter(Metrics.HOLD_EXPIRED);
    }

    public void dbAccepted() {
        dbOk.increment();
    }

    // 좌석 상태 변경과 1인 1선점 위반만 센다
    public void dbRejected(ErrorCode code) {
        if (code == ErrorCode.SEAT_STATE_CHANGED) {
            dbStateChanged.increment();
        } else if (code == ErrorCode.USER_HAS_HOLD) {
            dbUserHasHold.increment();
        }
    }

    public void gate(GateResult.Type type) {
        switch (type) {
            case OK -> gateOk.increment();
            case USER_HAS_HOLD -> gateUserHasHold.increment();
            case SEAT_TAKEN -> gateSeatTaken.increment();
            case COOLDOWN -> gateCooldown.increment();
        }
    }

    public void gateSkipped() {
        gateSkipped.increment();
    }

    public void gateError() {
        gateError.increment();
    }

    public void fallbackAcquired() {
        fallbackAcquired.increment();
    }

    public void fallbackRejected() {
        fallbackRejected.increment();
    }

    public void expired(int count) {
        expired.increment(count);
    }
}
