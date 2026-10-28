package com.example.ticketing.ops.domain;

import java.time.LocalDateTime;

public record RefundingHoldRow(long holdId, long userId, int seatCount, int totalAmount, LocalDateTime updatedAt) {
}
