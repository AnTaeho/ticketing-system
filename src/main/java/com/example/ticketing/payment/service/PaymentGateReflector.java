package com.example.ticketing.payment.service;

import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.payment.dto.PaymentSettlement;
import java.util.List;

// 결제 요청 경로와 대사가 결제 결론을 같은 규칙으로 게이트에 반영하게 한다. 게이트 꺼짐은 SeatGateReflector가 본다
public class PaymentGateReflector {

    private final SeatGateReflector gate;

    public PaymentGateReflector(SeatGateReflector gate) {
        this.gate = gate;
    }

    // 이번 호출이 상태를 바꿨을 때만 반영한다. 토큰이 같은 키만 건드리므로 좌석을 넉넉히 넘겨도 안전하다
    public void reflect(PaymentSettlement settlement, List<Long> seatIds) {
        if (!settlement.applied()) {
            return;
        }
        GateHold hold = new GateHold(settlement.userId(), settlement.performanceId(), settlement.holdToken(),
                seatIds);
        switch (settlement.outcome()) {
            case CONFIRMED -> gate.confirm(hold, settlement.gateCloseAt());
            case SEAT_LOST, DECLINED, CANCELLED -> gate.release(hold);
            default -> {
            }
        }
    }
}
