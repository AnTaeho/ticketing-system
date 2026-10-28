package com.example.ticketing.payment.domain;

import java.time.LocalDateTime;

// 대사가 집은 결제 행
public record DuePayment(long paymentId, String orderId, LocalDateTime createdAt) {
}
