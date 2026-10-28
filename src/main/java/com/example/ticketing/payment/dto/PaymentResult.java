package com.example.ticketing.payment.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.payment.domain.PaymentStatus;

// 결제 하나의 지금 결론. 예매 정보는 확정됐을 때만 있다
public record PaymentResult(
        long paymentId,
        PaymentOutcome outcome,
        PaymentStatus paymentStatus,
        HoldStatus holdStatus,
        Long bookingId,
        String bookingNumber
) {
}
