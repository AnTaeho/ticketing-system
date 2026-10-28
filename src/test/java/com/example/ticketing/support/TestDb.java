package com.example.ticketing.support;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

// 테스트가 DB 상태를 확인하고 시각을 당기는 SQL 모음. 운영 코드에 테스트용 조회를 두지 않으려고 여기에 둔다
@TestComponent
@RequiredArgsConstructor
public class TestDb {

    private static final String FAR_FUTURE = "'2999-12-31 00:00:00'";

    private final JdbcTemplate jdbcTemplate;

    public LocalDateTime dbNow() {
        return jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
    }

    public String showCreateTable(String table) {
        return jdbcTemplate.queryForObject("SHOW CREATE TABLE " + table, (rs, i) -> rs.getString(2));
    }

    public List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }

    // 배치 순서(열, 구역, 번호). 등급이 열로 정해지므로 앞쪽이 비싼 좌석이다
    public List<Long> seatIdsInLayoutOrder(long pid) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM seat WHERE performance_id = ? ORDER BY row_no, section, seat_no", Long.class, pid);
    }

    public Map<String, Object> seat(long seatId) {
        return jdbcTemplate.queryForMap("SELECT status, hold_id, held_until, price FROM seat WHERE id = ?", seatId);
    }

    public String seatStatus(long seatId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seatId);
    }

    public List<Long> heldSeats(long pid, long holdId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM seat WHERE performance_id = ? AND hold_id = ? AND status = 'HELD' ORDER BY id",
                Long.class, pid, holdId);
    }

    public long heldSeatCount(long pid) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'", Long.class, pid);
    }

    public String holdStatus(long holdId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    public String holdToken(long holdId) {
        return jdbcTemplate.queryForObject("SELECT hold_token FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    public LocalDateTime payDeadline(long holdId) {
        return jdbcTemplate.queryForObject("SELECT pay_deadline FROM seat_hold WHERE id = ?", LocalDateTime.class,
                holdId);
    }

    public long holdCount(long pid) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?", Long.class, pid);
    }

    // 선점의 만료 시각만 1초 전으로 당긴다. 좌석 held_until은 그대로다
    public void expireHold(long holdId) {
        jdbcTemplate.update("UPDATE seat_hold SET expires_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", holdId);
    }

    // 선점과 그 좌석의 기한을 함께 1분 전으로 당긴다
    public void expireHoldAndSeats(long holdId) {
        jdbcTemplate.update("UPDATE seat_hold SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = ?", holdId);
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 MINUTE WHERE hold_id = ?", holdId);
    }

    public Map<String, Object> payment(long paymentId) {
        return jdbcTemplate.queryForMap("SELECT hold_id, user_id, idempotency_key, order_id, amount, status, pg_tx_id, "
                + "last_error, next_check_at FROM booking_payment WHERE id = ?", paymentId);
    }

    public long paymentCountOfHold(long holdId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_payment WHERE hold_id = ?", Long.class,
                holdId);
    }

    public long bookingCountOfHold(long holdId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking WHERE hold_id = ?", Long.class, holdId);
    }

    public long bookingIdOfHold(long holdId) {
        return jdbcTemplate.queryForObject("SELECT id FROM booking WHERE hold_id = ?", Long.class, holdId);
    }

    public void makePaymentDue(long paymentId) {
        jdbcTemplate.update("UPDATE booking_payment SET next_check_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?",
                paymentId);
    }

    public void agePayment(long paymentId, int seconds) {
        jdbcTemplate.update("UPDATE booking_payment SET created_at = NOW(6) - INTERVAL ? SECOND WHERE id = ?",
                seconds, paymentId);
    }

    // 다음 확인 시각까지 남은 시간(ms). 과거면 음수
    public long paymentRecheckMs(long paymentId) {
        return jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), next_check_at) "
                + "FROM booking_payment WHERE id = ?", Long.class, paymentId) / 1_000;
    }

    public long outboxCount(String type, long aggregateId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_task WHERE task_type = ? AND aggregate_id = ?",
                Long.class, type, aggregateId);
    }

    public Map<String, Object> outboxTask(String type, long aggregateId) {
        return jdbcTemplate.queryForMap("SELECT status, attempt, payload, last_error, next_attempt_at, locked_until "
                + "FROM outbox_task WHERE task_type = ? AND aggregate_id = ?", type, aggregateId);
    }

    public long outboxTaskId(String type, long aggregateId) {
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_task WHERE task_type = ? AND aggregate_id = ?",
                Long.class, type, aggregateId);
    }

    public void expireOutboxLock(long taskId) {
        jdbcTemplate.update("UPDATE outbox_task SET locked_until = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", taskId);
    }

    public void makeOutboxDue(long taskId) {
        jdbcTemplate.update("UPDATE outbox_task SET next_attempt_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?",
                taskId);
    }

    // 다음 시도까지 남은 시간(ms)
    public long outboxRetryDelayMs(long taskId) {
        return jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), next_attempt_at) "
                + "FROM outbox_task WHERE id = ?", Long.class, taskId) / 1_000;
    }

    // 대사 테스트가 이전 테스트들이 남긴 결과 모름·요청 결제를 집지 않게 먼 미래로 미룬다(테스트 전용 DB)
    public void parkUnsettledPayments() {
        jdbcTemplate.update("UPDATE booking_payment SET next_check_at = " + FAR_FUTURE
                + " WHERE status IN ('REQUESTED', 'UNKNOWN')");
    }

    // 이전 테스트가 남긴 미완료 작업을 먼 미래로 미뤄 이 테스트의 집기에 섞이지 않게 한다(테스트 전용 DB)
    public void parkOutboxTasks() {
        jdbcTemplate.update(parkOutboxSql(""));
    }

    // 넘긴 작업 하나만 남기고 미완료 작업을 미룬다
    public void parkOutboxTasksExcept(String type, long aggregateId) {
        jdbcTemplate.update(parkOutboxSql(" AND NOT (task_type = ? AND aggregate_id = ?)"), type, aggregateId);
    }

    private static String parkOutboxSql(String extraCondition) {
        return "UPDATE outbox_task SET next_attempt_at = " + FAR_FUTURE + ", "
                + "locked_until = IF(status = 'PROCESSING', " + FAR_FUTURE + ", locked_until) "
                + "WHERE status IN ('PENDING', 'PROCESSING')" + extraCondition;
    }
}
