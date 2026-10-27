package com.example.ticketing.booking.show.dto;

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
