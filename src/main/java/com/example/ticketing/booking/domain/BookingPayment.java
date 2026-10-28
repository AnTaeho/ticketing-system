package com.example.ticketing.booking.domain;

// 예매를 산 결제. 취소 때 환불 작업을 남기고 환불 진행을 알리는 데만 쓴다
public record BookingPayment(long paymentId, String orderId, boolean refunded) {
}
