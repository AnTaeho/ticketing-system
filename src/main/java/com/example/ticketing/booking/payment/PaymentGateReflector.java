package com.example.ticketing.booking.payment;

import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import java.util.List;

// 결제 요청 경로와 대사가 결제 결론을 같은 규칙으로 게이트에 반영하게 한다
public class PaymentGateReflector {

    private final RedisSeatGate gate;
    private final boolean gateEnabled;

    public PaymentGateReflector(RedisSeatGate gate, boolean gateEnabled) {
        this.gate = gate;
        this.gateEnabled = gateEnabled;
    }

    // 이번 호출이 상태를 바꿨을 때만 반영한다. 토큰이 같은 키만 건드리므로 좌석을 넉넉히 넘겨도 안전하다
    public void reflect(PaymentSettlement settlement, List<Long> seatIds) {
        if (!gateEnabled || !settlement.applied()) {
            return;
        }
        switch (settlement.outcome()) {
            case CONFIRMED -> gate.confirmQuietly(settlement.performanceId(), settlement.userId(), seatIds,
                    settlement.holdToken(), settlement.gateCloseAt());
            case SEAT_LOST, DECLINED, CANCELLED -> gate.releaseQuietly(settlement.performanceId(),
                    settlement.userId(), seatIds, settlement.holdToken());
            default -> {
            }
        }
    }
}
