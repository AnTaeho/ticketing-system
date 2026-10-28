package com.example.ticketing.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// 200 CONFIRMED에만 예매 정보가 있다. 202는 {paymentId, status}
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(long paymentId, PaymentOutcome status, Long bookingId, String bookingNumber) {

    public static PaymentResponse from(PaymentResult result) {
        return new PaymentResponse(result.paymentId(), result.outcome(), result.bookingId(), result.bookingNumber());
    }
}
