package com.example.ticketing.hold.domain;

import java.util.List;

// 만료 처리를 위해 집은 hold 행
public record ClaimedHold(long holdId, long userId, long performanceId, String holdToken) {

    public GateHold gate(List<Long> seatIds) {
        return new GateHold(userId, performanceId, holdToken, seatIds);
    }
}
