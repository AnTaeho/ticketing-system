package com.example.ticketing.ops.dto;

import com.example.ticketing.ops.dto.OpsSummary.Attention;
import java.time.LocalDateTime;
import java.util.Map;

// 운영 요약 중 한 읽기 트랜잭션에서 DB로 센 부분. generatedAt은 그 트랜잭션의 DB 시각이다
public record OpsDbSnapshot(
        LocalDateTime generatedAt,
        OpsPerformance performance,
        Map<String, Long> seats,
        Map<String, Long> holds,
        Map<String, Long> payments,
        Map<String, Long> bookings,
        Map<String, Map<String, Long>> outbox,
        Attention attention
) {
}
