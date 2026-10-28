package com.example.ticketing.ops.domain;

import java.time.LocalDateTime;

// 집계 시각은 DB 시각이다. 회차 행과 같은 조회에서 읽는다
public record OpsPerformanceRow(long performanceId, String showTitle, String venueName, LocalDateTime startsAt,
                                LocalDateTime openAt, LocalDateTime gateCloseAt, LocalDateTime dbNow) {
}
