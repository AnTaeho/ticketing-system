package com.example.ticketing.payment.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

// 결제 상태 폴링 응답. 예매 정보는 확정됐을 때만 있다
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentStatusResponse(
        long paymentId,
        PaymentStatus status,
        HoldStatus holdStatus,
        Long bookingId,
        String bookingNumber
) {

    public static PaymentStatusResponse from(PaymentResult result) {
        return new PaymentStatusResponse(result.paymentId(), result.paymentStatus(), result.holdStatus(),
                result.bookingId(), result.bookingNumber());
    }
}
