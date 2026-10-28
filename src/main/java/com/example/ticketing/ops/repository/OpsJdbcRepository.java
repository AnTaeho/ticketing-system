package com.example.ticketing.ops.repository;

import com.example.ticketing.ops.domain.FailedTaskRow;
import com.example.ticketing.ops.domain.OpsPerformanceRow;
import com.example.ticketing.ops.domain.OutboxStatusCount;
import com.example.ticketing.ops.domain.RefundingHoldRow;
import com.example.ticketing.ops.domain.StalePaymentRow;
import com.example.ticketing.ops.domain.StatusCount;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// 시연용 집계라 여러 기능의 테이블을 읽기만 한다. 상태마다 GROUP BY 한 번씩 읽는다
@Component
@RequiredArgsConstructor
public class OpsJdbcRepository {

    private static final String PERFORMANCE_SQL = """
            SELECT p.id, s.title, s.venue_name, p.starts_at, p.open_at, p.gate_close_at, NOW(6) AS db_now
              FROM performance p
              JOIN show_info s ON s.id = p.show_id
             WHERE p.id = :pid""";

    private static final String SEAT_SQL =
            "SELECT status, COUNT(*) AS cnt FROM seat WHERE performance_id = :pid GROUP BY status";
    private static final String HOLD_SQL =
            "SELECT status, COUNT(*) AS cnt FROM seat_hold WHERE performance_id = :pid GROUP BY status";
    private static final String BOOKING_SQL =
            "SELECT status, COUNT(*) AS cnt FROM booking WHERE performance_id = :pid GROUP BY status";
    private static final String PAYMENT_SQL = """
            SELECT bp.status, COUNT(*) AS cnt
              FROM booking_payment bp
              JOIN seat_hold h ON h.id = bp.hold_id
             WHERE h.performance_id = :pid
             GROUP BY bp.status""";

    // TICKET_ISSUED의 대상은 예매, REFUND_REQUESTED의 대상은 결제다
    private static final String OUTBOX_OF_PERFORMANCE = """
            ((o.task_type = 'TICKET_ISSUED'
              AND o.aggregate_id IN (SELECT b.id FROM booking b WHERE b.performance_id = :pid))
             OR (o.task_type = 'REFUND_REQUESTED'
              AND o.aggregate_id IN (SELECT bp.id FROM booking_payment bp
                                       JOIN seat_hold h ON h.id = bp.hold_id
                                      WHERE h.performance_id = :pid)))""";
    private static final String OUTBOX_SQL = "SELECT o.task_type, o.status, COUNT(*) AS cnt FROM outbox_task o WHERE "
            + OUTBOX_OF_PERFORMANCE + " GROUP BY o.task_type, o.status";
    private static final String FAILED_TASK_SQL = "SELECT o.id, o.task_type, o.aggregate_id, o.attempt, o.last_error, "
            + "o.updated_at FROM outbox_task o WHERE o.status = 'FAILED' AND " + OUTBOX_OF_PERFORMANCE
            + " ORDER BY o.id DESC LIMIT :limit";

    private static final String REFUNDING_SQL = """
            SELECT h.id, h.user_id, h.seat_count, h.total_amount, h.updated_at
              FROM seat_hold h
             WHERE h.performance_id = :pid AND h.status = 'REFUNDING'
             ORDER BY h.id DESC LIMIT :limit""";

    // 결제가 아직 열린 상태(PaymentStatus.isOpen)로 오래 남은 것
    private static final String STALE_PAYMENT_SQL = """
            SELECT bp.id, bp.hold_id, bp.status, bp.created_at, bp.last_error
              FROM booking_payment bp
              JOIN seat_hold h ON h.id = bp.hold_id
             WHERE h.performance_id = :pid
               AND bp.status IN ('UNKNOWN', 'REQUESTED')
               AND bp.created_at < NOW(6) - INTERVAL :staleMicros MICROSECOND
             ORDER BY bp.id DESC LIMIT :limit""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<OpsPerformanceRow> findPerformance(long performanceId) {
        return jdbcTemplate.query(PERFORMANCE_SQL, Map.of("pid", performanceId),
                (rs, i) -> new OpsPerformanceRow(rs.getLong("id"), rs.getString("title"), rs.getString("venue_name"),
                        rs.getObject("starts_at", LocalDateTime.class), rs.getObject("open_at", LocalDateTime.class),
                        rs.getObject("gate_close_at", LocalDateTime.class),
                        rs.getObject("db_now", LocalDateTime.class)))
                .stream().findFirst();
    }

    public List<StatusCount> countSeats(long performanceId) {
        return countBy(SEAT_SQL, performanceId);
    }

    public List<StatusCount> countHolds(long performanceId) {
        return countBy(HOLD_SQL, performanceId);
    }

    public List<StatusCount> countPayments(long performanceId) {
        return countBy(PAYMENT_SQL, performanceId);
    }

    public List<StatusCount> countBookings(long performanceId) {
        return countBy(BOOKING_SQL, performanceId);
    }

    public List<OutboxStatusCount> countOutboxTasks(long performanceId) {
        return jdbcTemplate.query(OUTBOX_SQL, Map.of("pid", performanceId),
                (rs, i) -> new OutboxStatusCount(rs.getString("task_type"), rs.getString("status"), rs.getLong("cnt")));
    }

    // 최신순
    public List<FailedTaskRow> findFailedTasks(long performanceId, int limit) {
        return jdbcTemplate.query(FAILED_TASK_SQL, Map.of("pid", performanceId, "limit", limit),
                (rs, i) -> new FailedTaskRow(rs.getLong("id"), rs.getString("task_type"), rs.getLong("aggregate_id"),
                        rs.getInt("attempt"), rs.getString("last_error"),
                        rs.getObject("updated_at", LocalDateTime.class)));
    }

    public List<RefundingHoldRow> findRefundingHolds(long performanceId, int limit) {
        return jdbcTemplate.query(REFUNDING_SQL, Map.of("pid", performanceId, "limit", limit),
                (rs, i) -> new RefundingHoldRow(rs.getLong("id"), rs.getLong("user_id"), rs.getInt("seat_count"),
                        rs.getInt("total_amount"), rs.getObject("updated_at", LocalDateTime.class)));
    }

    public List<StalePaymentRow> findStalePayments(long performanceId, long staleMicros, int limit) {
        return jdbcTemplate.query(STALE_PAYMENT_SQL,
                Map.of("pid", performanceId, "staleMicros", staleMicros, "limit", limit),
                (rs, i) -> new StalePaymentRow(rs.getLong("id"), rs.getLong("hold_id"), rs.getString("status"),
                        rs.getObject("created_at", LocalDateTime.class), rs.getString("last_error")));
    }

    private List<StatusCount> countBy(String sql, long performanceId) {
        return jdbcTemplate.query(sql, Map.of("pid", performanceId),
                (rs, i) -> new StatusCount(rs.getString(1), rs.getLong("cnt")));
    }
}
