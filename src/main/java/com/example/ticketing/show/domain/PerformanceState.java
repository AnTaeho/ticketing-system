package com.example.ticketing.show.domain;

import java.time.LocalDateTime;

public enum PerformanceState {
    BEFORE_OPEN,
    OPEN,
    CLOSED;

    // 회차 열림 판정은 여기 한 곳이다. now에는 DB 시각을 넘기고, 선점 전 사전 확인만 서버 시계를 넘긴다
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
