package com.example.ticketing.hold.dto;

import com.example.ticketing.seat.domain.SeatInfo;
import java.time.LocalDateTime;
import java.util.List;

public record CreateHoldResult(long holdId, LocalDateTime expiresAt, List<SeatInfo> seats, int totalAmount) {
}
