package com.example.ticketing.show.dto;

import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.PerformanceState;
import java.time.LocalDateTime;

public record PerformanceResult(Long id, LocalDateTime startsAt, LocalDateTime openAt, PerformanceState state) {

    // dbNow는 DB 시각이다
    public static PerformanceResult from(Performance performance, LocalDateTime dbNow) {
        return new PerformanceResult(performance.getId(), performance.getStartsAt(), performance.getOpenAt(),
                PerformanceState.of(performance.getOpenAt(), performance.getStartsAt(), dbNow));
    }
}
