package com.example.ticketing.payment.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.payment.domain.PaymentStatus;

// 결제·hold 상태 조합을 사용자에게 돌려줄 결과 하나로 줄인다
public enum PaymentOutcome {
    PROCESSING,
    UNKNOWN,
    CONFIRMED,
    SEAT_LOST,
    DECLINED,
    CANCELLED;

    public static PaymentOutcome of(PaymentStatus paymentStatus, HoldStatus holdStatus) {
        return switch (paymentStatus) {
            case REQUESTED -> PROCESSING;
            case UNKNOWN -> UNKNOWN;
            case APPROVED -> holdStatus == HoldStatus.CONFIRMED ? CONFIRMED : SEAT_LOST;
            case DECLINED -> DECLINED;
            case CANCELLED -> CANCELLED;
        };
    }
}
