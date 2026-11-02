package com.example.ticketing.ops.domain;

import java.time.LocalDateTime;

public record RefundingHold(long holdId, long userId, int seatCount, int totalAmount, LocalDateTime updatedAt) {
}
