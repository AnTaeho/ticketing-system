package com.example.ticketing.ops.service;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.ops.dto.OpsSummary;
import com.example.ticketing.ops.dto.OpsSummary.Attention;
import com.example.ticketing.ops.dto.OpsSummary.CounterView;
import com.example.ticketing.ops.dto.OpsSummary.FailedTask;
import com.example.ticketing.ops.dto.OpsSummary.GateKeys;
import com.example.ticketing.ops.dto.OpsSummary.PerformanceInfo;
import com.example.ticketing.ops.dto.OpsSummary.Queue;
import com.example.ticketing.ops.dto.OpsSummary.RefundingHold;
import com.example.ticketing.ops.dto.OpsSummary.StalePayment;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.seat.domain.SeatStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// 시연용 집계. 상태마다 GROUP BY 한 번씩 읽고, Outbox는 예매·결제를 거쳐 이 회차 것만 센다
@Slf4j
@Service
public class OpsSummaryService {

    static final int LIST_LIMIT = 20;
    static final int STALE_PAYMENT_SECONDS = 30;
    private static final long SCAN_COUNT = 200;

    // 태그 순서는 값을 이어 붙이는 순서다
    private static final Map<String, List<String>> COUNTERS = counters();

    private static final String PERFORMANCE_SQL = """
            SELECT p.id, s.title, s.venue_name, p.starts_at, p.open_at, p.gate_close_at, NOW(6) AS db_now
              FROM performance p
              JOIN show_info s ON s.id = p.show_id
             WHERE p.id = ?""";

    private static final String SEAT_SQL =
            "SELECT status, COUNT(*) FROM seat WHERE performance_id = ? GROUP BY status";
    private static final String HOLD_SQL =
            "SELECT status, COUNT(*) FROM seat_hold WHERE performance_id = ? GROUP BY status";
    private static final String BOOKING_SQL =
            "SELECT status, COUNT(*) FROM booking WHERE performance_id = ? GROUP BY status";
    private static final String PAYMENT_SQL = """
            SELECT bp.status, COUNT(*)
              FROM booking_payment bp
              JOIN seat_hold h ON h.id = bp.hold_id
             WHERE h.performance_id = ?
             GROUP BY bp.status""";

    // TICKET_ISSUED의 대상은 예매, REFUND_REQUESTED의 대상은 결제다
    private static final String OUTBOX_OF_PERFORMANCE = """
            ((o.task_type = 'TICKET_ISSUED'
              AND o.aggregate_id IN (SELECT b.id FROM booking b WHERE b.performance_id = ?))
             OR (o.task_type = 'REFUND_REQUESTED'
              AND o.aggregate_id IN (SELECT bp.id FROM booking_payment bp
                                       JOIN seat_hold h ON h.id = bp.hold_id
                                      WHERE h.performance_id = ?)))""";
    private static final String OUTBOX_SQL = "SELECT o.task_type, o.status, COUNT(*) AS cnt FROM outbox_task o WHERE "
            + OUTBOX_OF_PERFORMANCE + " GROUP BY o.task_type, o.status";
    private static final String FAILED_TASK_SQL = "SELECT o.id, o.task_type, o.aggregate_id, o.attempt, o.last_error, "
            + "o.updated_at FROM outbox_task o WHERE o.status = 'FAILED' AND " + OUTBOX_OF_PERFORMANCE
            + " ORDER BY o.id DESC LIMIT " + LIST_LIMIT;

    private static final String REFUNDING_SQL = """
            SELECT id, user_id, seat_count, total_amount, updated_at
              FROM seat_hold
             WHERE performance_id = ? AND status = 'REFUNDING'
             ORDER BY id DESC LIMIT %d""".formatted(LIST_LIMIT);

    private static final String STALE_PAYMENT_SQL = """
            SELECT bp.id, bp.hold_id, bp.status, bp.created_at, bp.last_error
              FROM booking_payment bp
              JOIN seat_hold h ON h.id = bp.hold_id
             WHERE h.performance_id = ?
               AND bp.status IN ('UNKNOWN', 'REQUESTED')
               AND bp.created_at < NOW(6) - INTERVAL %d SECOND
             ORDER BY bp.id DESC LIMIT %d""".formatted(STALE_PAYMENT_SECONDS, LIST_LIMIT);

    private final JdbcTemplate jdbcTemplate;
    private final TicketingRedis bookingRedis;
    private final MeterRegistry meterRegistry;

    public OpsSummaryService(JdbcTemplate jdbcTemplate, TicketingRedis bookingRedis, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookingRedis = bookingRedis;
        this.meterRegistry = meterRegistry;
    }

    public OpsSummary summarize(long performanceId) {
        List<PerformanceRow> found = jdbcTemplate.query(PERFORMANCE_SQL, (rs, i) -> new PerformanceRow(
                new PerformanceInfo(rs.getLong("id"), rs.getString("title"), rs.getString("venue_name"),
                        rs.getObject("starts_at", LocalDateTime.class), rs.getObject("open_at", LocalDateTime.class),
                        rs.getObject("gate_close_at", LocalDateTime.class)),
                rs.getObject("db_now", LocalDateTime.class)), performanceId);
        if (found.isEmpty()) {
            throw new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND, Map.of("performanceId", performanceId));
        }
        PerformanceRow performance = found.get(0);

        Map<String, Long> seats = countBy(SEAT_SQL, SeatStatus.values(), performanceId);
        Map<String, Long> holds = countBy(HOLD_SQL, HoldStatus.values(), performanceId);
        Map<String, Long> payments = countBy(PAYMENT_SQL, PaymentStatus.values(), performanceId);
        Map<String, Long> bookings = countBy(BOOKING_SQL, BookingStatus.values(), performanceId);
        Map<String, Map<String, Long>> outbox = outbox(performanceId);
        Attention attention = attention(performanceId);
        RedisPart redis = readRedis(performanceId);

        return new OpsSummary(performance.dbNow(), performance.info(), seats, holds, payments, bookings, outbox,
                attention, redis.available(), redis.queue(), redis.gateKeys(), counterValues());
    }

    private Map<String, Long> countBy(String sql, Enum<?>[] states, long performanceId) {
        Map<String, Long> counts = zeroes(states);
        jdbcTemplate.query(sql, rs -> {
            counts.put(rs.getString(1), rs.getLong(2));
        }, performanceId);
        return counts;
    }

    private Map<String, Map<String, Long>> outbox(long performanceId) {
        Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
        for (OutboxTaskType type : OutboxTaskType.values()) {
            counts.put(type.name(), zeroes(OutboxTaskStatus.values()));
        }
        jdbcTemplate.query(OUTBOX_SQL, rs -> {
            counts.computeIfAbsent(rs.getString("task_type"), type -> zeroes(OutboxTaskStatus.values()))
                    .put(rs.getString("status"), rs.getLong("cnt"));
        }, performanceId, performanceId);
        return counts;
    }

    private Attention attention(long performanceId) {
        List<FailedTask> failed = jdbcTemplate.query(FAILED_TASK_SQL, (rs, i) -> new FailedTask(rs.getLong("id"),
                rs.getString("task_type"), rs.getLong("aggregate_id"), rs.getInt("attempt"),
                rs.getString("last_error"), rs.getObject("updated_at", LocalDateTime.class)),
                performanceId, performanceId);
        List<RefundingHold> refunding = jdbcTemplate.query(REFUNDING_SQL, (rs, i) -> new RefundingHold(
                rs.getLong("id"), rs.getLong("user_id"), rs.getInt("seat_count"), rs.getInt("total_amount"),
                rs.getObject("updated_at", LocalDateTime.class)), performanceId);
        List<StalePayment> stale = jdbcTemplate.query(STALE_PAYMENT_SQL, (rs, i) -> new StalePayment(
                rs.getLong("id"), rs.getLong("hold_id"), rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class), rs.getString("last_error")), performanceId);
        return new Attention(failed, refunding, stale);
    }

    // 한 명령이라도 실패하면 Redis 칸 전체를 비운다. 연결이 죽었으면 나머지도 타임아웃까지 기다리게 되므로
    private RedisPart readRedis(long performanceId) {
        try {
            StringRedisTemplate redis = bookingRedis.template();
            Long waiting = redis.opsForZSet().zCard(RedisKeys.queueWaiting(performanceId));
            Long processing = redis.opsForZSet().zCard(RedisKeys.queueProcessing(performanceId));
            long seatKeys = countKeys(redis, "seat:" + RedisKeys.tag(performanceId) + ":*");
            long userHoldKeys = countKeys(redis, "userhold:" + RedisKeys.tag(performanceId) + ":*");
            return new RedisPart(true, new Queue(orZero(waiting), orZero(processing)),
                    new GateKeys(seatKeys, userHoldKeys));
        } catch (RuntimeException e) {
            log.warn("[booking ops] 회차 {} Redis 집계 실패: {}", performanceId, e.toString());
            return new RedisPart(false, new Queue(null, null), new GateKeys(null, null));
        }
    }

    // 회차 하나의 키만 훑는다. 데모 규모라 SCAN으로 세도 충분하다
    private static long countKeys(StringRedisTemplate redis, String pattern) {
        long count = 0;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build())) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }

    private Map<String, CounterView> counterValues() {
        Map<String, CounterView> result = new LinkedHashMap<>();
        COUNTERS.forEach((name, tags) -> {
            Map<String, Long> values = new TreeMap<>();
            for (Counter counter : meterRegistry.find(name).counters()) {
                String key = tags.isEmpty() ? "total" : String.join("/",
                        tags.stream().map(tag -> String.valueOf(counter.getId().getTag(tag))).toList());
                values.merge(key, Math.round(counter.count()), Long::sum);
            }
            long total = values.values().stream().mapToLong(Long::longValue).sum();
            result.put(name, new CounterView(tags, values, total));
        });
        return result;
    }

    private static Map<String, List<String>> counters() {
        Map<String, List<String>> counters = new LinkedHashMap<>();
        counters.put("booking.hold.gate", List.of("result"));
        counters.put("booking.hold.db", List.of("result"));
        counters.put("booking.hold.fallback", List.of("result"));
        counters.put("booking.hold.expired", List.of());
        counters.put("booking.seatmap.rebuild", List.of("source"));
        counters.put("booking.payment.reconcile", List.of("result"));
        counters.put("booking.outbox.task", List.of("type", "result"));
        counters.put("booking.redis.reflect.failure", List.of("op"));
        counters.put("booking.gate.orphan.removed", List.of("kind"));
        return counters;
    }

    private static Map<String, Long> zeroes(Enum<?>[] states) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Arrays.stream(states).forEach(state -> counts.put(state.name(), 0L));
        return counts;
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private record PerformanceRow(PerformanceInfo info, LocalDateTime dbNow) {
    }

    private record RedisPart(boolean available, Queue queue, GateKeys gateKeys) {
    }
}
