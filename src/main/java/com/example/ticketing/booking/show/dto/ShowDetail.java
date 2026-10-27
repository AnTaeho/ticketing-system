package com.example.ticketing.booking.show.dto;

import java.util.List;

public record ShowDetail(
        Long id,
        String title,
        String venueName,
        String description,
        List<PerformanceView> performances
) {
}
