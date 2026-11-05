package com.example.ticketing.booking.service;

// 예매 취소가 부르는 환불 창구. 결제 기능이 구현한다
public interface PaymentRefunder {

    // PG 환불이 확정되고 결제를 CANCELLED로 바꿨으면 true. 실패하거나 모르면 false
    boolean refund(long paymentId);
}
