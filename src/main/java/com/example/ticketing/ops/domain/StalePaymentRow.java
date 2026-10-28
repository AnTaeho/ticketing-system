package com.example.ticketing.ops.domain;

import java.time.LocalDateTime;

public record StalePaymentRow(long paymentId, long holdId, String status, LocalDateTime createdAt,
                              String lastError) {
}
