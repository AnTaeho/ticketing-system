package com.example.ticketing.payment.dto;

// notFoundCancelDue는 집은 시각(DB) 기준으로 결제 생성 후 not-found-cancel-after가 지났는지다
public record ClaimedPayment(long paymentId, String orderId, boolean notFoundCancelDue) {
}
