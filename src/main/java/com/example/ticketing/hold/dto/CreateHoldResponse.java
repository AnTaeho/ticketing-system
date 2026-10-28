package com.example.ticketing.hold.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CreateHoldResponse(long holdId, LocalDateTime expiresAt, List<HoldSeatResponse> seats, int totalAmount) {

    public static CreateHoldResponse from(CreateHoldResult result) {
        return new CreateHoldResponse(result.holdId(), result.expiresAt(),
                result.seats().stream().map(HoldSeatResponse::from).toList(), result.totalAmount());
    }
}
