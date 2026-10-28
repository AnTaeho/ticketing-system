package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import java.time.LocalDateTime;

public record ActiveHoldResponse(long holdId, HoldStatus status, LocalDateTime expiresAt) {

    public static ActiveHoldResponse from(ActiveHoldResult result) {
        return new ActiveHoldResponse(result.holdId(), result.status(), result.expiresAt());
    }
}
