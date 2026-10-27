package com.example.ticketing.booking.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.BookingRedis;
import com.example.ticketing.booking.common.TestBookingRedis;
import com.example.ticketing.booking.hold.gate.BookingKeys;
import com.example.ticketing.booking.ops.dto.OpsSummary;
import com.example.ticketing.booking.ops.dto.OpsSummary.FailedTask;
import com.example.ticketing.booking.ops.dto.OpsSummary.StalePayment;
import com.example.ticketing.booking.queue.QueueKeys;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

// 상태를 섞은 회차를 JdbcTemplate으로 직접 만든다. 다른 테스트의 스캔이 집지 않게 만든 행은 끝나면 지운다
@BookingIntegrationTest
class OpsSummaryServiceTest {

    @Autowired
    private OpsSummaryService service;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BookingRedis bookingRedis;
    @Autowired
    private BookingProperties props;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;

    private final List<Long> performances = new ArrayList<>();
    private final List<Long> holds = new ArrayList<>();
    private final List<Long> payments = new ArrayList<>();
    private final List<Long> bookings = new ArrayList<>();
    private final List<Long> tasks = new ArrayList<>();
    private BookingRedis deadRedis;

    @AfterEach
    void tearDown() {
        delete("outbox_task", tasks);
        delete("booking", bookings);
        delete("booking_payment", payments);
        delete("seat_hold", holds);
        performances.forEach(redisKeyCleaner::clean);
        if (deadRedis != null) {
            deadRedis.destroy();
        }
    }

    @Test
    @DisplayName("좌석·선점·결제·예매·Outbox를 상태별로 세고, 사람이 볼 목록과 Redis 칸을 채운다")
    void countsMixedPerformance() {
        Scenario s = mixedPerformance();
        StringRedisTemplate redis = bookingRedis.template();
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(QueueKeys.waiting(s.pid), "w1", now);
        redis.opsForZSet().add(QueueKeys.waiting(s.pid), "w2", now + 1);
        redis.opsForZSet().add(QueueKeys.processing(s.pid), "p1", now + 600_000);
        redis.opsForValue().set(BookingKeys.seat(s.pid, s.seats.get(0)), "token");
        redis.opsForValue().set(BookingKeys.seat(s.pid, s.seats.get(1)), "token");
        redis.opsForValue().set(BookingKeys.userHold(s.pid, 1L), "token");
        // 이웃 회차의 게이트 키는 세지 않는다
        redis.opsForValue().set(BookingKeys.seat(s.otherPid, 1L), "token");

        OpsSummary summary = service.summarize(s.pid);

        assertThat(summary.performance().performanceId()).isEqualTo(s.pid);
        assertThat(summary.performance().showTitle()).startsWith("테스트 공연");
        assertThat(summary.generatedAt()).isNotNull();
        assertThat(summary.seats()).containsExactly(
                Map.entry("AVAILABLE", 5L), Map.entry("HELD", 3L), Map.entry("SOLD", 2L));
        assertThat(summary.holds()).containsExactly(
                Map.entry("HELD", 1L), Map.entry("PAYING", 1L), Map.entry("CONFIRMED", 2L),
                Map.entry("EXPIRED", 1L), Map.entry("RELEASED", 0L), Map.entry("REFUNDING", 1L));
        assertThat(summary.payments()).containsExactly(
                Map.entry("REQUESTED", 1L), Map.entry("UNKNOWN", 1L), Map.entry("APPROVED", 2L),
                Map.entry("DECLINED", 0L), Map.entry("CANCELLED", 0L));
        assertThat(summary.bookings()).containsExactly(Map.entry("CONFIRMED", 1L), Map.entry("CANCELLED", 1L));
        assertThat(summary.outbox().get("TICKET_ISSUED")).containsExactly(
                Map.entry("PENDING", 1L), Map.entry("PROCESSING", 0L), Map.entry("DONE", 1L), Map.entry("FAILED", 0L));
        assertThat(summary.outbox().get("REFUND_REQUESTED")).containsExactly(
                Map.entry("PENDING", 1L), Map.entry("PROCESSING", 0L), Map.entry("DONE", 0L), Map.entry("FAILED", 1L));

        assertThat(summary.attention().failedTasks()).extracting(FailedTask::id).containsExactly(s.failedTask);
        FailedTask failed = summary.attention().failedTasks().get(0);
        assertThat(failed.type()).isEqualTo("REFUND_REQUESTED");
        assertThat(failed.aggregateId()).isEqualTo(s.cancelledPayment);
        assertThat(failed.attempt()).isEqualTo(5);
        assertThat(failed.lastError()).isEqualTo("PG 취소 UNKNOWN");
        assertThat(summary.attention().refundingHolds()).singleElement()
                .satisfies(h -> {
                    assertThat(h.holdId()).isEqualTo(s.refundingHold);
                    assertThat(h.seatCount()).isEqualTo(1);
                    assertThat(h.totalAmount()).isEqualTo(150_000);
                });
        // 막 시작한 REQUESTED는 오래된 결제가 아니다
        assertThat(summary.attention().stalePayments()).extracting(StalePayment::paymentId)
                .containsExactly(s.stalePayment);
        assertThat(summary.attention().stalePayments().get(0).status()).isEqualTo("UNKNOWN");
        assertThat(summary.attention().stalePayments().get(0).lastError()).isEqualTo("PG 응답 시간 초과");

        assertThat(summary.redisAvailable()).isTrue();
        assertThat(summary.queue().waiting()).isEqualTo(2L);
        assertThat(summary.queue().processing()).isEqualTo(1L);
        assertThat(summary.gateKeys().seat()).isEqualTo(2L);
        assertThat(summary.gateKeys().userHold()).isEqualTo(1L);

        assertThat(summary.counters()).containsOnlyKeys("booking.hold.gate", "booking.hold.db",
                "booking.hold.fallback", "booking.hold.expired", "booking.seatmap.rebuild", "booking.payment.reconcile",
                "booking.outbox.task", "booking.redis.reflect.failure", "booking.gate.orphan.removed");
        assertThat(summary.counters().get("booking.hold.gate").values())
                .containsKeys("ok", "seat_taken", "user_has_hold", "cooldown", "skipped");
        assertThat(summary.counters().get("booking.outbox.task").tags()).containsExactly("type", "result");
    }

    @Test
    @DisplayName("Redis를 못 읽으면 redisAvailable=false이고 Redis 칸만 null, DB 집계와 카운터는 그대로 준다")
    void redisFailureLeavesOnlyRedisCellsEmpty() throws IOException {
        Scenario s = mixedPerformance();
        deadRedis = TestBookingRedis.connectTo("localhost", closedPort(), props);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        meters.counter("booking.hold.gate", "result", "ok").increment(3);
        meters.counter("booking.hold.gate", "result", "seat_taken").increment();
        meters.counter("booking.hold.expired").increment(2);
        meters.counter("booking.outbox.task", "type", "TICKET_ISSUED", "result", "success").increment(4);
        OpsSummaryService broken = new OpsSummaryService(jdbc, deadRedis, meters);

        OpsSummary summary = broken.summarize(s.pid);

        assertThat(summary.redisAvailable()).isFalse();
        assertThat(summary.queue().waiting()).isNull();
        assertThat(summary.queue().processing()).isNull();
        assertThat(summary.gateKeys().seat()).isNull();
        assertThat(summary.gateKeys().userHold()).isNull();
        assertThat(summary.seats()).containsEntry("HELD", 3L).containsEntry("SOLD", 2L);
        assertThat(summary.payments()).containsEntry("UNKNOWN", 1L);
        assertThat(summary.attention().failedTasks()).hasSize(1);
        assertThat(summary.counters().get("booking.hold.gate").values())
                .containsExactly(Map.entry("ok", 3L), Map.entry("seat_taken", 1L));
        assertThat(summary.counters().get("booking.hold.gate").total()).isEqualTo(4L);
        assertThat(summary.counters().get("booking.hold.expired").values()).containsExactly(Map.entry("total", 2L));
        assertThat(summary.counters().get("booking.outbox.task").values())
                .containsExactly(Map.entry("TICKET_ISSUED/success", 4L));
        assertThat(summary.counters().get("booking.payment.reconcile").values()).isEmpty();
        assertThat(summary.counters().get("booking.payment.reconcile").total()).isZero();
    }

    @Test
    @DisplayName("없는 회차는 PERFORMANCE_NOT_FOUND")
    void unknownPerformance() {
        assertThatThrownBy(() -> service.summarize(Long.MAX_VALUE - 7))
                .isInstanceOf(BookingException.class)
                .extracting(e -> ((BookingException) e).getCode())
                .isEqualTo(BookingErrorCode.PERFORMANCE_NOT_FOUND);
    }

    // 좌석 10개: HELD 3(선점 HELD 2석, PAYING 1석), SOLD 2(확정 1건), 나머지 빈자리
    private Scenario mixedPerformance() {
        long pid = track(fixtures.newPerformance(1, 2, 5));
        long otherPid = track(fixtures.newPerformance(1, 1, 2));
        List<Long> seats = jdbc.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
        long user = fixtures.newUser();

        long held = hold(pid, user, "HELD", 2, 300_000);
        markSeats(held, "HELD", seats.get(0), seats.get(1));

        long paying = hold(pid, user, "PAYING", 1, 150_000);
        markSeats(paying, "HELD", seats.get(2));
        long stalePayment = payment(paying, user, "UNKNOWN", 60, "PG 응답 시간 초과");

        long confirmed = hold(pid, user, "CONFIRMED", 2, 300_000);
        markSeats(confirmed, "SOLD", seats.get(3), seats.get(4));
        long confirmedPayment = payment(confirmed, user, "APPROVED", 120, null);
        long confirmedBooking = booking(confirmed, confirmedPayment, user, pid, "CONFIRMED");
        task("TICKET_ISSUED", confirmedBooking, "DONE", 1, null);

        long cancelled = hold(pid, user, "CONFIRMED", 1, 150_000);
        long cancelledPayment = payment(cancelled, user, "APPROVED", 120, null);
        long cancelledBooking = booking(cancelled, cancelledPayment, user, pid, "CANCELLED");
        task("TICKET_ISSUED", cancelledBooking, "PENDING", 0, null);
        long failedTask = task("REFUND_REQUESTED", cancelledPayment, "FAILED", 5, "PG 취소 UNKNOWN");

        hold(pid, user, "EXPIRED", 1, 150_000);

        long refunding = hold(pid, user, "REFUNDING", 1, 150_000);
        long refundingPayment = payment(refunding, user, "REQUESTED", 0, null);
        task("REFUND_REQUESTED", refundingPayment, "PENDING", 0, null);

        // 이웃 회차의 실패 작업·오래된 결제는 이 회차 집계에 섞이지 않는다
        long otherHold = hold(otherPid, user, "PAYING", 1, 90_000);
        long otherPayment = payment(otherHold, user, "UNKNOWN", 90, "다른 회차");
        task("REFUND_REQUESTED", otherPayment, "FAILED", 5, "다른 회차");

        return new Scenario(pid, otherPid, seats, failedTask, cancelledPayment, refunding, stalePayment);
    }

    private long track(long pid) {
        performances.add(pid);
        return pid;
    }

    private long hold(long pid, long user, String status, int seatCount, int amount) {
        long id = insert("""
                INSERT INTO seat_hold (performance_id, user_id, hold_token, status, seat_count, total_amount,
                                       expires_at, pay_deadline, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(6) + INTERVAL 1 DAY, NOW(6) + INTERVAL 1 DAY, NOW(6), NOW(6))""",
                pid, user, UUID.randomUUID().toString(), status, seatCount, amount);
        holds.add(id);
        return id;
    }

    private void markSeats(long holdId, String status, Long... seatIds) {
        for (Long seatId : seatIds) {
            jdbc.update("UPDATE seat SET status = ?, hold_id = ? WHERE id = ?", status, holdId, seatId);
        }
    }

    private long payment(long holdId, long user, String status, int ageSeconds, String lastError) {
        long id = insert("""
                INSERT INTO booking_payment (hold_id, user_id, idempotency_key, order_id, amount, status, last_error,
                                             next_check_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 150000, ?, ?, '2999-12-31 00:00:00',
                        NOW(6) - INTERVAL ? SECOND, NOW(6))""",
                holdId, user, UUID.randomUUID().toString(), UUID.randomUUID().toString(), status, lastError,
                ageSeconds);
        payments.add(id);
        return id;
    }

    private long booking(long holdId, long paymentId, long user, long pid, String status) {
        long id = insert("""
                INSERT INTO booking (booking_number, hold_id, payment_id, user_id, performance_id, total_amount,
                                     status, created_at, cancelled_at)
                VALUES (?, ?, ?, ?, ?, 150000, ?, NOW(6), IF(? = 'CANCELLED', NOW(6), NULL))""",
                "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 15), holdId, paymentId, user, pid,
                status, status);
        bookings.add(id);
        return id;
    }

    private long task(String type, long aggregateId, String status, int attempt, String lastError) {
        long id = insert("""
                INSERT INTO outbox_task (task_type, aggregate_id, payload, status, attempt, next_attempt_at,
                                         last_error, created_at, updated_at)
                VALUES (?, ?, '{}', ?, ?, '2999-12-31 00:00:00', ?, NOW(6), NOW(6))""",
                type, aggregateId, status, attempt, lastError);
        tasks.add(id);
        return id;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void delete(String table, List<Long> ids) {
        ids.forEach(id -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id));
        ids.clear();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record Scenario(long pid, long otherPid, List<Long> seats, long failedTask, long cancelledPayment,
                            long refundingHold, long stalePayment) {
    }
}
