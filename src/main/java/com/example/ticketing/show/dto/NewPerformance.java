package com.example.ticketing.show.dto;

import java.time.LocalDateTime;

public record NewPerformance(
        Long showId,
        LocalDateTime startsAt,
        LocalDateTime openAt,
        LocalDateTime gateCloseAt,
        Integer sections,
        Integer rows,
        Integer seatsPerRow
) {
}
