package com.example.ticketing.booking.hold.dto;

import com.example.ticketing.booking.hold.domain.HoldStatus;
import java.time.LocalDateTime;

// 회차마다 한 사람이 가진 진행 중(HELD·PAYING) 선점
public record ActiveHold(long holdId, HoldStatus status, LocalDateTime expiresAt) {
}
