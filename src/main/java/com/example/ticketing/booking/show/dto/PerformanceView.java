package com.example.ticketing.booking.show.dto;

import com.example.ticketing.booking.show.domain.Performance;
import com.example.ticketing.booking.show.domain.PerformanceState;
import java.time.LocalDateTime;

public record PerformanceView(Long id, LocalDateTime startsAt, LocalDateTime openAt, PerformanceState state) {

    public static PerformanceView of(Performance performance, LocalDateTime dbNow) {
        return new PerformanceView(performance.getId(), performance.getStartsAt(), performance.getOpenAt(),
                PerformanceState.of(performance.getOpenAt(), performance.getStartsAt(), dbNow));
    }
}
