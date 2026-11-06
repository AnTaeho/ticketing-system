package com.example.ticketing.payment.dto;

import com.example.ticketing.payment.domain.PaymentStatus;

// 결제 상태를 사용자에게 돌려줄 결과로 바꾼다
public enum PaymentOutcome {
    PROCESSING,
    UNKNOWN,
    CONFIRMED,
    DECLINED,
    CANCELLED;

    public static PaymentOutcome of(PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case REQUESTED -> PROCESSING;
            case UNKNOWN -> UNKNOWN;
            case APPROVED -> CONFIRMED;
            case DECLINED -> DECLINED;
            case CANCELLED -> CANCELLED;
        };
    }
}
