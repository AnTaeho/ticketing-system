package com.example.ticketing.payment.dto;

import com.example.ticketing.payment.domain.Payment;
import java.time.Duration;
import java.time.LocalDateTime;

// notFoundCancelDue는 DB 시각 기준으로 결제 생성 후 not-found-cancel-after가 지났는지다
public record DuePayment(long paymentId, String orderId, boolean notFoundCancelDue) {

    public static DuePayment from(Payment payment, LocalDateTime dbNow, Duration notFoundCancelAfter) {
        return new DuePayment(payment.getId(), payment.getOrderId(),
                !payment.getCreatedAt().plus(notFoundCancelAfter).isAfter(dbNow));
    }
}
