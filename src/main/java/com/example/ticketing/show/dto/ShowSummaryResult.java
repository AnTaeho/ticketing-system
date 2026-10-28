package com.example.ticketing.show.dto;

import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.Show;
import java.time.LocalDateTime;

// nextPerformance는 아직 시작하지 않은 가장 이른 회차다. 없으면 null
public record ShowSummaryResult(Long id, String title, String venueName, PerformanceResult nextPerformance) {

    public static ShowSummaryResult from(Show show, Performance next, LocalDateTime dbNow) {
        return new ShowSummaryResult(show.getId(), show.getTitle(), show.getVenueName(),
                next == null ? null : PerformanceResult.from(next, dbNow));
    }
}
