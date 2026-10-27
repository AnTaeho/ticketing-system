package com.example.ticketing.booking.show.domain;

import java.time.LocalDateTime;

public enum PerformanceState {
    BEFORE_OPEN,
    OPEN,
    CLOSED;

    // now는 DB 시각을 넘긴다
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
