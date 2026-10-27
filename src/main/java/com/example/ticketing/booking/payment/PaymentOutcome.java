package com.example.ticketing.booking.payment;

import com.example.ticketing.booking.hold.domain.HoldStatus;
import com.example.ticketing.booking.payment.domain.BookingPaymentStatus;

// 결제·hold 상태 조합을 사용자에게 돌려줄 결과 하나로 줄인다
public enum PaymentOutcome {
    PROCESSING,
    UNKNOWN,
    CONFIRMED,
    SEAT_LOST,
    DECLINED,
    CANCELLED;

    public static PaymentOutcome of(BookingPaymentStatus paymentStatus, HoldStatus holdStatus) {
        return switch (paymentStatus) {
            case REQUESTED -> PROCESSING;
            case UNKNOWN -> UNKNOWN;
            case APPROVED -> holdStatus == HoldStatus.CONFIRMED ? CONFIRMED : SEAT_LOST;
            case DECLINED -> DECLINED;
            case CANCELLED -> CANCELLED;
        };
    }
}
