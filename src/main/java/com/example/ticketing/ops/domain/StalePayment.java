package com.example.ticketing.ops.domain;

import com.example.ticketing.payment.domain.PaymentStatus;
import java.time.LocalDateTime;

public record StalePayment(long paymentId, long holdId, PaymentStatus status, LocalDateTime createdAt,
                           String lastError) {
}
