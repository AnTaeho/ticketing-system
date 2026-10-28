package com.example.ticketing.hold.dto;

import java.time.LocalDateTime;
import java.util.List;

public record HoldCreated(long holdId, LocalDateTime expiresAt, List<HoldSeatView> seats, int totalAmount) {
}
