package com.example.ticketing.booking.payment.fakepg;

public record FakePgApproveRequest(String orderId, Integer amount) {
}
