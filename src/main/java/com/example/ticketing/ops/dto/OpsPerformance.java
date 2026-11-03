package com.example.ticketing.ops.dto;

import java.time.LocalDateTime;

public record OpsPerformance(long performanceId, String showTitle, String venueName, LocalDateTime startsAt,
                             LocalDateTime openAt, LocalDateTime gateCloseAt) {
}
