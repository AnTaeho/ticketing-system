package com.example.ticketing.seat.domain;

import com.example.ticketing.show.domain.PerformanceState;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 생성 뒤 바뀌지 않는 회차 시각과 좌석. seats는 좌석 ID 오름차순이다
public record PerformanceInfo(
        long performanceId,
        LocalDateTime openAt,
        LocalDateTime startsAt,
        LocalDateTime gateCloseAt,
        Map<Long, SeatInfo> seats
) {

    // SeatRepository의 JPQL이 부른다. 좌석은 withSeats로 붙인다
    public PerformanceInfo(long performanceId, LocalDateTime openAt, LocalDateTime startsAt,
                           LocalDateTime gateCloseAt) {
        this(performanceId, openAt, startsAt, gateCloseAt, Map.of());
    }

    // seats는 좌석 ID 오름차순으로 넘긴다
    public PerformanceInfo withSeats(List<SeatInfo> seats) {
        Map<Long, SeatInfo> byId = new LinkedHashMap<>();
        seats.forEach(seat -> byId.put(seat.id(), seat));
        return new PerformanceInfo(performanceId, openAt, startsAt, gateCloseAt, Collections.unmodifiableMap(byId));
    }

    public PerformanceState stateAt(LocalDateTime now) {
        return PerformanceState.of(openAt, startsAt, now);
    }
}
