package com.example.ticketing.booking.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// 200 CONFIRMED에만 예매 정보가 있다. 202는 {paymentId, status}
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(long paymentId, Status status, Long bookingId, String bookingNumber) {

    public enum Status {
        CONFIRMED,
        PROCESSING,
        UNKNOWN
    }

    public static PaymentResponse confirmed(long paymentId, long bookingId, String bookingNumber) {
        return new PaymentResponse(paymentId, Status.CONFIRMED, bookingId, bookingNumber);
    }

    public static PaymentResponse pending(long paymentId, Status status) {
        return new PaymentResponse(paymentId, status, null, null);
    }
}
