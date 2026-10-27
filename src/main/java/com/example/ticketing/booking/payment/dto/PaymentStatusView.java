package com.example.ticketing.booking.payment.dto;

import com.example.ticketing.booking.hold.domain.HoldStatus;
import com.example.ticketing.booking.payment.domain.BookingPaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

// 결제 상태 폴링 응답. 예매 정보는 확정됐을 때만 있다
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentStatusView(
        long paymentId,
        BookingPaymentStatus status,
        HoldStatus holdStatus,
        Long bookingId,
        String bookingNumber
) {
}
