package com.example.ticketing.booking.ops.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// Redis를 못 읽으면 queue·gateKeys의 값만 null이고 redisAvailable이 false다. counters는 서버가 켜진 뒤 모든 회차를 합친 수
public record OpsSummary(
        LocalDateTime generatedAt,
        PerformanceInfo performance,
        Map<String, Long> seats,
        Map<String, Long> holds,
        Map<String, Long> payments,
        Map<String, Long> bookings,
        Map<String, Map<String, Long>> outbox,
        Attention attention,
        boolean redisAvailable,
        Queue queue,
        GateKeys gateKeys,
        Map<String, CounterView> counters
) {

    public record PerformanceInfo(long performanceId, String showTitle, String venueName, LocalDateTime startsAt,
                                  LocalDateTime openAt, LocalDateTime gateCloseAt) {
    }

    public record Attention(List<FailedTask> failedTasks, List<RefundingHold> refundingHolds,
                            List<StalePayment> stalePayments) {
    }

    public record FailedTask(long id, String type, long aggregateId, int attempt, String lastError,
                             LocalDateTime updatedAt) {
    }

    public record RefundingHold(long holdId, long userId, int seatCount, int totalAmount, LocalDateTime updatedAt) {
    }

    public record StalePayment(long paymentId, long holdId, String status, LocalDateTime createdAt,
                               String lastError) {
    }

    public record Queue(Long waiting, Long processing) {
    }

    public record GateKeys(Long seat, Long userHold) {
    }

    // values의 키는 태그 값(태그가 둘이면 "TICKET_ISSUED/success"), 태그가 없으면 "total"
    public record CounterView(List<String> tags, Map<String, Long> values, long total) {
    }
}
