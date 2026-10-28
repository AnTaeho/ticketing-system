package com.example.ticketing.show.dto;

public record CreatePerformanceResponse(Long performanceId, int seatCount) {

    public static CreatePerformanceResponse from(CreatePerformanceResult result) {
        return new CreatePerformanceResponse(result.performanceId(), result.seatCount());
    }
}
