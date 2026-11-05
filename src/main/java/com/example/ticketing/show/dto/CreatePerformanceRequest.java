package com.example.ticketing.show.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

// 필수 값은 여기서, 시각 순서와 크기 범위는 ShowAdminService가 검사한다
public record CreatePerformanceRequest(
        @NotNull Long showId,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime openAt,
        @NotNull Integer sections,
        @NotNull Integer rows,
        @NotNull Integer seatsPerRow
) {

    public CreatePerformanceCommand toCommand() {
        return new CreatePerformanceCommand(showId, startsAt, openAt, sections, rows, seatsPerRow);
    }
}
