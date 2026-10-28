package com.example.ticketing.show.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

// 시각 순서와 크기 범위는 ShowAdminService가 검사한다
public record CreatePerformanceRequest(
        @NotNull Long showId,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime openAt,
        LocalDateTime gateCloseAt,
        @NotNull Integer sections,
        @NotNull Integer rows,
        @NotNull Integer seatsPerRow
) {

    public CreatePerformanceCommand toCommand() {
        return new CreatePerformanceCommand(showId, startsAt, openAt, gateCloseAt, sections, rows, seatsPerRow);
    }
}
