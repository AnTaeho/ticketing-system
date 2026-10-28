package com.example.ticketing.hold.dto;

import java.util.List;

// 좌석 수·중복·회차 검사는 INVALID_SEAT_SELECTION으로 답하려고 서비스가 한다
public record CreateHoldRequest(List<Long> seatIds, String queueToken) {

    public CreateHoldCommand toCommand(long userId, long performanceId) {
        return new CreateHoldCommand(userId, performanceId, seatIds, queueToken);
    }
}
