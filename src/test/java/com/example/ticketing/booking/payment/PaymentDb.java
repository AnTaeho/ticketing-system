package com.example.ticketing.booking.payment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

// 결제 테스트가 DB 상태를 확인하는 조회 모음
public class PaymentDb {

    private final JdbcTemplate jdbc;

    public PaymentDb(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> seatIds(long pid) {
        return jdbc.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }

    public String holdStatus(long holdId) {
        return jdbc.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    public String holdToken(long holdId) {
        return jdbc.queryForObject("SELECT hold_token FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    public LocalDateTime payDeadline(long holdId) {
        return jdbc.queryForObject("SELECT pay_deadline FROM seat_hold WHERE id = ?", LocalDateTime.class, holdId);
    }

    public Map<String, Object> seat(long seatId) {
        return jdbc.queryForMap("SELECT status, hold_id, held_until, price FROM seat WHERE id = ?", seatId);
    }

    public Map<String, Object> payment(long paymentId) {
        return jdbc.queryForMap("SELECT hold_id, user_id, idempotency_key, order_id, amount, status, pg_tx_id, "
                + "last_error, next_check_at FROM booking_payment WHERE id = ?", paymentId);
    }

    public long paymentCountOfHold(long holdId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM booking_payment WHERE hold_id = ?", Long.class, holdId);
    }

    public long bookingCountOfHold(long holdId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM booking WHERE hold_id = ?", Long.class, holdId);
    }

    public long outboxCount(String type, long aggregateId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_task WHERE task_type = ? AND aggregate_id = ?",
                Long.class, type, aggregateId);
    }

    public Map<String, Object> outbox(String type, long aggregateId) {
        return jdbc.queryForMap("SELECT status, attempt, payload, next_attempt_at, locked_until "
                + "FROM outbox_task WHERE task_type = ? AND aggregate_id = ?", type, aggregateId);
    }

    public void expireHold(long holdId) {
        jdbc.update("UPDATE seat_hold SET expires_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", holdId);
    }

    public void makeDue(long paymentId) {
        jdbc.update("UPDATE booking_payment SET next_check_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", paymentId);
    }

    public void ageCreatedAt(long paymentId, int seconds) {
        jdbc.update("UPDATE booking_payment SET created_at = NOW(6) - INTERVAL ? SECOND WHERE id = ?",
                seconds, paymentId);
    }

    // 다음 확인 시각까지 남은 시간(ms). 과거면 음수
    public long recheckMs(long paymentId) {
        return jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), next_check_at) FROM booking_payment "
                + "WHERE id = ?", Long.class, paymentId) / 1_000;
    }

    // 대사 테스트가 이전 테스트들이 남긴 결과 모름·요청 결제를 집지 않게 먼 미래로 미룬다(테스트 전용 DB)
    public void parkUnsettledPayments() {
        jdbc.update("UPDATE booking_payment SET next_check_at = '2999-12-31 00:00:00' "
                + "WHERE status IN ('REQUESTED', 'UNKNOWN')");
    }

    public LocalDateTime dbNow() {
        return jdbc.queryForObject("SELECT NOW(6)", LocalDateTime.class);
    }
}
