package com.example.ticketing.hold.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CreateHoldResult(long holdId, LocalDateTime expiresAt, List<HoldSeatResult> seats, int totalAmount) {
}
