package com.example.ticketing.show.dto;

import java.time.LocalDateTime;

// gateCloseAt이 null이면 서비스가 기본값을 정한다
public record CreatePerformanceCommand(
        Long showId,
        LocalDateTime startsAt,
        LocalDateTime openAt,
        LocalDateTime gateCloseAt,
        Integer sections,
        Integer rows,
        Integer seatsPerRow
) {
}
