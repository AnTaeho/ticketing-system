package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import java.time.LocalDateTime;
import java.util.List;

public record HoldDetailResponse(
        long holdId,
        long performanceId,
        HoldStatus status,
        LocalDateTime expiresAt,
        LocalDateTime payDeadline,
        LocalDateTime serverNow,
        List<HoldSeatResponse> seats,
        int totalAmount,
        Long paymentId
) {

    public static HoldDetailResponse from(HoldDetailResult result) {
        return new HoldDetailResponse(result.holdId(), result.performanceId(), result.status(), result.expiresAt(),
                result.payDeadline(), result.serverNow(), result.seats().stream().map(HoldSeatResponse::from).toList(),
                result.totalAmount(), result.paymentId());
    }
}
