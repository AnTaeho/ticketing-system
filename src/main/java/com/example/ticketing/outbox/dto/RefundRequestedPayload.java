package com.example.ticketing.outbox.dto;

// 컴포넌트 순서가 저장되는 JSON의 키 순서다
public record RefundRequestedPayload(long paymentId, String orderId) implements OutboxPayload {
}
