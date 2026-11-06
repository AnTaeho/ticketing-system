package com.example.ticketing.show.dto;

import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.Show;
import java.time.LocalDateTime;

// 다른 기능이 읽는 회차 하나와 그 공연
public record PerformanceSummary(
        long performanceId,
        String showTitle,
        String venueName,
        LocalDateTime openAt,
        LocalDateTime startsAt
) {

    public static PerformanceSummary from(Performance performance, Show show) {
        return new PerformanceSummary(performance.getId(), show.getTitle(), show.getVenueName(),
                performance.getOpenAt(), performance.getStartsAt());
    }

    // dbNow는 DB 시각이다
    public boolean hasStartedAt(LocalDateTime dbNow) {
        return !startsAt.isAfter(dbNow);
    }
}
