package com.example.ticketing.ops.service;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.ops.domain.StatusCount;
import com.example.ticketing.ops.dto.OpsDbSnapshot;
import com.example.ticketing.ops.dto.OpsPerformance;
import com.example.ticketing.ops.dto.OpsSummary.Attention;
import com.example.ticketing.ops.repository.OpsRepository;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.seat.domain.SeatStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 집계 쿼리를 읽기 전용 트랜잭션 하나에서 돌려 표끼리 같은 순간을 세게 한다. 상태마다 0부터 채운 뒤 DB가 센 값을 덮는다
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpsSummaryReader {

    private static final Limit LIST_LIMIT = Limit.of(20);
    // 운영 화면에 오래 걸리는 결제로 올리는 기준
    private static final Duration STALE_PAYMENT_AFTER = Duration.ofSeconds(30);

    private static final List<PaymentStatus> OPEN_PAYMENTS = Arrays.stream(PaymentStatus.values())
            .filter(PaymentStatus::isOpen).toList();

    private final OpsRepository opsRepository;
    private final DbClock dbClock;

    // 집계 시각은 DB 시각이고, 오래된 결제도 이 시각을 기준으로 고른다. Outbox는 예매·결제를 거쳐 이 회차 것만 센다
    public OpsDbSnapshot read(long performanceId) {
        OpsPerformance performance = opsRepository.findPerformance(performanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND,
                        Map.of("performanceId", performanceId)));
        LocalDateTime dbNow = dbClock.now();
        return new OpsDbSnapshot(dbNow, performance,
                counts(SeatStatus.values(), opsRepository.countSeats(performanceId)),
                counts(HoldStatus.values(), opsRepository.countHolds(performanceId)),
                counts(PaymentStatus.values(), opsRepository.countPayments(performanceId)),
                counts(BookingStatus.values(), opsRepository.countBookings(performanceId)),
                outbox(performanceId), attention(performanceId, dbNow));
    }

    private Map<String, Map<String, Long>> outbox(long performanceId) {
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        for (OutboxTaskType type : OutboxTaskType.values()) {
            counts.put(type.name(), zeroes(OutboxTaskStatus.values()));
        }
        opsRepository.countOutboxTasks(performanceId).forEach(row -> counts
                .computeIfAbsent(row.taskType().name(), type -> zeroes(OutboxTaskStatus.values()))
                .put(row.status().name(), row.count()));
        return counts;
    }

    private Attention attention(long performanceId, LocalDateTime dbNow) {
        return new Attention(opsRepository.findFailedTasks(performanceId, LIST_LIMIT),
                opsRepository.findRefundingHolds(performanceId, LIST_LIMIT),
                opsRepository.findStalePayments(performanceId, OPEN_PAYMENTS, dbNow.minus(STALE_PAYMENT_AFTER),
                        LIST_LIMIT));
    }

    private static Map<String, Long> counts(Enum<?>[] states, List<StatusCount> rows) {
        Map<String, Long> counts = zeroes(states);
        rows.forEach(row -> counts.put(row.status().name(), row.count()));
        return counts;
    }

    private static Map<String, Long> zeroes(Enum<?>[] states) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Arrays.stream(states).forEach(state -> counts.put(state.name(), 0L));
        return counts;
    }
}
