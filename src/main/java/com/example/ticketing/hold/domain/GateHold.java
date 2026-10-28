package com.example.ticketing.hold.domain;

import java.util.List;

// 게이트 키를 가리키는 값. 키 값은 holdToken이고 seatIds는 이 선점이 쥐었거나 쥐려는 좌석이다
public record GateHold(long userId, long performanceId, String holdToken, List<Long> seatIds) {

    public GateHold {
        seatIds = List.copyOf(seatIds);
    }
}
