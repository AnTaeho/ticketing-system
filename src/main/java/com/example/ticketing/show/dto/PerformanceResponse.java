package com.example.ticketing.show.dto;

import com.example.ticketing.show.domain.PerformanceState;
import java.time.LocalDateTime;

public record PerformanceResponse(Long id, LocalDateTime startsAt, LocalDateTime openAt, PerformanceState state) {

    // 다음 회차가 없으면 null을 그대로 내보낸다
    public static PerformanceResponse from(PerformanceResult result) {
        if (result == null) {
            return null;
        }
        return new PerformanceResponse(result.id(), result.startsAt(), result.openAt(), result.state());
    }
}
