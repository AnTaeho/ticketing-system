package com.example.ticketing.show.dto;

import java.util.List;

public record ShowDetailResponse(
        Long id,
        String title,
        String venueName,
        String description,
        List<PerformanceResponse> performances
) {

    public static ShowDetailResponse from(ShowDetailResult result) {
        return new ShowDetailResponse(result.id(), result.title(), result.venueName(), result.description(),
                result.performances().stream().map(PerformanceResponse::from).toList());
    }
}
