package com.example.ticketing.payment.service;

import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.payment.dto.PaymentSettlement;

// 결제 요청 경로와 대사가 결제 결론을 같은 규칙으로 게이트에 반영하게 한다. 게이트 꺼짐은 SeatGateReflector가 본다
public class PaymentGateReflector {

    private final SeatGateReflector gate;

    public PaymentGateReflector(SeatGateReflector gate) {
        this.gate = gate;
    }

    // 이번 호출이 좌석을 바꿨을 때만 반영한다
    public void reflect(PaymentSettlement settlement) {
        settlement.gateReflection().ifPresent(reflection -> {
            switch (settlement.result().outcome()) {
                case CONFIRMED -> gate.confirm(reflection.gate(), reflection.gateCloseAt());
                case SEAT_LOST, DECLINED, CANCELLED -> gate.release(reflection.gate());
                default -> {
                }
            }
        });
    }
}
