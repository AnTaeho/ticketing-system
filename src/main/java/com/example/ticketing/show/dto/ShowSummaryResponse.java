package com.example.ticketing.show.dto;

public record ShowSummaryResponse(Long id, String title, String venueName, PerformanceResponse nextPerformance) {

    public static ShowSummaryResponse from(ShowSummaryResult result) {
        return new ShowSummaryResponse(result.id(), result.title(), result.venueName(),
                PerformanceResponse.from(result.nextPerformance()));
    }
}
