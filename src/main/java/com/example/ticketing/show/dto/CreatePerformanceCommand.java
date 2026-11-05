package com.example.ticketing.show.dto;

import java.time.LocalDateTime;

public record CreatePerformanceCommand(
        long showId,
        LocalDateTime startsAt,
        LocalDateTime openAt,
        int sections,
        int rows,
        int seatsPerRow
) {
}
