package com.example.ticketing.show.dto;

import com.example.ticketing.show.domain.Performance;
import com.example.ticketing.show.domain.Show;
import java.time.LocalDateTime;
import java.util.List;

public record ShowDetailResult(
        Long id,
        String title,
        String venueName,
        String description,
        List<PerformanceResult> performances
) {

    // performances는 넘긴 순서 그대로 둔다
    public static ShowDetailResult from(Show show, List<Performance> performances, LocalDateTime dbNow) {
        return new ShowDetailResult(show.getId(), show.getTitle(), show.getVenueName(), show.getDescription(),
                performances.stream().map(p -> PerformanceResult.from(p, dbNow)).toList());
    }
}
