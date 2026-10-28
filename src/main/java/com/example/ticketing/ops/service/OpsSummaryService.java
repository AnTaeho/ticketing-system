package com.example.ticketing.ops.service;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.jdbc.SqlIntervals;
import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.ops.domain.OpsPerformanceRow;
import com.example.ticketing.ops.domain.StatusCount;
import com.example.ticketing.ops.dto.OpsSummary;
import com.example.ticketing.ops.dto.OpsSummary.Attention;
import com.example.ticketing.ops.dto.OpsSummary.FailedTask;
import com.example.ticketing.ops.dto.OpsSummary.GateKeys;
import com.example.ticketing.ops.dto.OpsSummary.PerformanceInfo;
import com.example.ticketing.ops.dto.OpsSummary.Queue;
import com.example.ticketing.ops.dto.OpsSummary.RefundingHold;
import com.example.ticketing.ops.dto.OpsSummary.StalePayment;
import com.example.ticketing.ops.repository.OpsJdbcRepository;
import com.example.ticketing.ops.repository.OpsRedisStore;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.seat.domain.SeatStatus;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 시연용 집계. 상태마다 0부터 채운 뒤 DB가 센 값을 덮고, Outbox는 예매·결제를 거쳐 이 회차 것만 센다
@Slf4j
@Service
public class OpsSummaryService {

    private static final int LIST_LIMIT = 20;

    private final OpsJdbcRepository opsJdbcRepository;
    private final OpsRedisStore opsRedisStore;
    private final OpsMetricsReader metricsReader;
    private final ThrottledWarn redisWarn;
    private final long stalePaymentMicros;

    public OpsSummaryService(OpsJdbcRepository opsJdbcRepository, OpsRedisStore opsRedisStore,
                             OpsMetricsReader metricsReader, LongSupplier nanoClock, TicketingProperties properties) {
        this.opsJdbcRepository = opsJdbcRepository;
        this.opsRedisStore = opsRedisStore;
        this.metricsReader = metricsReader;
        this.redisWarn = new ThrottledWarn(log, nanoClock);
        this.stalePaymentMicros = SqlIntervals.micros(properties.ops().stalePaymentAfter());
    }

    public OpsSummary summarize(long performanceId) {
        OpsPerformanceRow performance = opsJdbcRepository.findPerformance(performanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND,
                        Map.of("performanceId", performanceId)));

        Map<String, Long> seats = counts(SeatStatus.values(), opsJdbcRepository.countSeats(performanceId));
        Map<String, Long> holds = counts(HoldStatus.values(), opsJdbcRepository.countHolds(performanceId));
        Map<String, Long> payments = counts(PaymentStatus.values(), opsJdbcRepository.countPayments(performanceId));
        Map<String, Long> bookings = counts(BookingStatus.values(), opsJdbcRepository.countBookings(performanceId));
        Map<String, Map<String, Long>> outbox = outbox(performanceId);
        Attention attention = attention(performanceId);
        RedisPart redis = readRedis(performanceId);

        return new OpsSummary(performance.dbNow(), info(performance), seats, holds, payments, bookings, outbox,
                attention, redis.available(), redis.queue(), redis.gateKeys(), metricsReader.counterValues());
    }

    private Map<String, Map<String, Long>> outbox(long performanceId) {
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        for (OutboxTaskType type : OutboxTaskType.values()) {
            counts.put(type.name(), zeroes(OutboxTaskStatus.values()));
        }
        opsJdbcRepository.countOutboxTasks(performanceId).forEach(row -> counts
                .computeIfAbsent(row.taskType(), type -> zeroes(OutboxTaskStatus.values()))
                .put(row.status(), row.count()));
        return counts;
    }

    private Attention attention(long performanceId) {
        List<FailedTask> failed = opsJdbcRepository.findFailedTasks(performanceId, LIST_LIMIT).stream()
                .map(r -> new FailedTask(r.id(), r.taskType(), r.aggregateId(), r.attempt(), r.lastError(),
                        r.updatedAt()))
                .toList();
        List<RefundingHold> refunding = opsJdbcRepository.findRefundingHolds(performanceId, LIST_LIMIT).stream()
                .map(r -> new RefundingHold(r.holdId(), r.userId(), r.seatCount(), r.totalAmount(), r.updatedAt()))
                .toList();
        List<StalePayment> stale = opsJdbcRepository.findStalePayments(performanceId, stalePaymentMicros, LIST_LIMIT)
                .stream()
                .map(r -> new StalePayment(r.paymentId(), r.holdId(), r.status(), r.createdAt(), r.lastError()))
                .toList();
        return new Attention(failed, refunding, stale);
    }

    // 한 명령이라도 실패하면 Redis 칸 전체를 비운다. 연결이 죽었으면 나머지도 타임아웃까지 기다리게 되므로
    private RedisPart readRedis(long performanceId) {
        try {
            Queue queue = new Queue(opsRedisStore.waitingCount(performanceId),
                    opsRedisStore.processingCount(performanceId));
            GateKeys gateKeys = new GateKeys(opsRedisStore.seatKeyCount(performanceId),
                    opsRedisStore.userHoldKeyCount(performanceId));
            return new RedisPart(true, queue, gateKeys);
        } catch (RedisUnavailableException e) {
            redisWarn.warn("[ops] Redis 집계 실패 performanceId={} cause={}", performanceId, RedisFailures.describe(e));
            return new RedisPart(false, new Queue(null, null), new GateKeys(null, null));
        }
    }

    private static PerformanceInfo info(OpsPerformanceRow row) {
        return new PerformanceInfo(row.performanceId(), row.showTitle(), row.venueName(), row.startsAt(),
                row.openAt(), row.gateCloseAt());
    }

    private static Map<String, Long> counts(Enum<?>[] states, List<StatusCount> rows) {
        Map<String, Long> counts = zeroes(states);
        rows.forEach(row -> counts.put(row.status(), row.count()));
        return counts;
    }

    private static Map<String, Long> zeroes(Enum<?>[] states) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Arrays.stream(states).forEach(state -> counts.put(state.name(), 0L));
        return counts;
    }

    private record RedisPart(boolean available, Queue queue, GateKeys gateKeys) {
    }
}
