package com.example.ticketing.seat.domain;

import com.example.ticketing.show.domain.PerformanceState;
import java.time.LocalDateTime;
import java.util.Map;

// 생성 뒤 바뀌지 않는 회차 시각과 좌석. seats는 좌석 ID 오름차순이다
public record PerformanceInfo(
        long performanceId,
        long showId,
        LocalDateTime openAt,
        LocalDateTime startsAt,
        LocalDateTime gateCloseAt,
        Map<Long, SeatInfo> seats
) {

    public PerformanceState stateAt(LocalDateTime now) {
        return PerformanceState.of(openAt, startsAt, now);
    }
}
