package com.example.ticketing.ops.service;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.ops.domain.StatusCount;
import com.example.ticketing.ops.dto.OpsPerformance;
import com.example.ticketing.ops.dto.OpsSummary;
import com.example.ticketing.ops.dto.OpsSummary.Attention;
import com.example.ticketing.ops.dto.OpsSummary.GateKeys;
import com.example.ticketing.ops.dto.OpsSummary.Queue;
import com.example.ticketing.ops.repository.OpsRepository;
import com.example.ticketing.ops.repository.OpsRedisStore;
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
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 시연용 집계. 상태마다 0부터 채운 뒤 DB가 센 값을 덮고, Outbox는 예매·결제를 거쳐 이 회차 것만 센다
@Slf4j
@Service
public class OpsSummaryService {

    private static final int LIST_LIMIT = 20;
    // 운영 화면에 오래 걸리는 결제로 올리는 기준
    static final Duration STALE_PAYMENT_AFTER = Duration.ofSeconds(30);

    private static final List<PaymentStatus> OPEN_PAYMENTS = Arrays.stream(PaymentStatus.values())
            .filter(PaymentStatus::isOpen).toList();

    private final OpsRepository opsRepository;
    private final OpsRedisStore opsRedisStore;
    private final OpsMetricsReader metricsReader;
    private final DbClock dbClock;
    private final ThrottledWarn redisWarn;

    public OpsSummaryService(OpsRepository opsRepository, OpsRedisStore opsRedisStore, OpsMetricsReader metricsReader,
                             DbClock dbClock, LongSupplier nanoClock) {
        this.opsRepository = opsRepository;
        this.opsRedisStore = opsRedisStore;
        this.metricsReader = metricsReader;
        this.dbClock = dbClock;
        this.redisWarn = new ThrottledWarn(log, nanoClock);
    }

    // 집계 시각은 DB 시각이고, 오래된 결제도 이 시각을 기준으로 고른다
    public OpsSummary summarize(long performanceId) {
        OpsPerformance performance = opsRepository.findPerformance(performanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND,
                        Map.of("performanceId", performanceId)));
        LocalDateTime dbNow = dbClock.now();

        Map<String, Long> seats = counts(SeatStatus.values(), opsRepository.countSeats(performanceId));
        Map<String, Long> holds = counts(HoldStatus.values(), opsRepository.countHolds(performanceId));
        Map<String, Long> payments = counts(PaymentStatus.values(), opsRepository.countPayments(performanceId));
        Map<String, Long> bookings = counts(BookingStatus.values(), opsRepository.countBookings(performanceId));
        Map<String, Map<String, Long>> outbox = outbox(performanceId);
        Attention attention = attention(performanceId, dbNow);
        RedisPart redis = readRedis(performanceId);

        return new OpsSummary(dbNow, performance, seats, holds, payments, bookings, outbox,
                attention, redis.available(), redis.queue(), redis.gateKeys(), metricsReader.counterValues());
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

    private record RedisPart(boolean available, Queue queue, GateKeys gateKeys) {
    }
}
