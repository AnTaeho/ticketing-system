package com.example.ticketing.show.domain;

import java.time.LocalDateTime;

public enum PerformanceState {
    BEFORE_OPEN,
    OPEN,
    CLOSED;

    // 회차 열림 판정은 여기 한 곳이다. now는 DB 시각이나 DB 시각 추정값을 넘긴다
    public static PerformanceState of(LocalDateTime openAt, LocalDateTime startsAt, LocalDateTime now) {
        if (now.isBefore(openAt)) {
            return BEFORE_OPEN;
        }
        if (now.isBefore(startsAt)) {
            return OPEN;
        }
        return CLOSED;
    }
}
