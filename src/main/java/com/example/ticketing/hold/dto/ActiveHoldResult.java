package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.domain.SeatHold;
import java.time.LocalDateTime;

// 회차마다 한 사람이 가진 진행 중(HELD·PAYING) 선점
public record ActiveHoldResult(long holdId, HoldStatus status, LocalDateTime expiresAt) {

    public static ActiveHoldResult from(SeatHold hold) {
        return new ActiveHoldResult(hold.getId(), hold.getStatus(), hold.getExpiresAt());
    }
}
