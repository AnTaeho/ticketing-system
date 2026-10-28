package com.example.ticketing.payment.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.jdbc.RowCounts;
import com.example.ticketing.common.text.Texts;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.repository.PaymentRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기와 다음 확인 시각 갱신을 한 트랜잭션에서 한다. 커밋 뒤에는 다른 서버가 같은 결제를 다시 집지 않는다
@Component
public class PaymentReconcileTransaction {

    private static final String CLAIM_SQL = """
            SELECT id, order_id, created_at FROM booking_payment
             WHERE status IN ('REQUESTED', 'UNKNOWN') AND next_check_at <= NOW(6)
             ORDER BY id LIMIT :batch
             FOR UPDATE SKIP LOCKED""";

    private static final String LEASE_SQL = """
            UPDATE booking_payment
               SET next_check_at = DATE_ADD(NOW(6), INTERVAL :leaseMicros MICROSECOND), updated_at = NOW(6)
             WHERE id IN (:ids)""";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PaymentRepository paymentRepository;
    private final DbClock dbClock;
    private final Duration notFoundCancelAfter;
    // 한 건의 PG 조회가 끝나기 전에 다른 서버가 같은 결제를 집지 않게 미뤄 둔다
    private final long claimLeaseMicros;

    public PaymentReconcileTransaction(NamedParameterJdbcTemplate jdbcTemplate,
                                       PaymentRepository paymentRepository, DbClock dbClock,
                                       TicketingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.paymentRepository = paymentRepository;
        this.dbClock = dbClock;
        this.notFoundCancelAfter = properties.payment().notFoundCancelAfter();
        this.claimLeaseMicros = micros(properties.payment().reconcileClaimLease());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedPayment> claim(int batch) {
        List<Row> rows = jdbcTemplate.query(CLAIM_SQL, Map.of("batch", batch),
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("order_id"),
                        rs.getObject("created_at", LocalDateTime.class)));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Row::paymentId).toList();
        jdbcTemplate.update(LEASE_SQL, Map.of("ids", ids, "leaseMicros", claimLeaseMicros));

        LocalDateTime now = dbClock.now();
        return rows.stream()
                .map(r -> new ClaimedPayment(r.paymentId(), r.orderId(),
                        !r.createdAt().plus(notFoundCancelAfter).isAfter(now)))
                .toList();
    }

    // 끝난 결제는 건드리지 않는다. 바꿨으면 true
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean reschedule(long paymentId, Duration delay, String lastError) {
        return RowCounts.changedOne(paymentRepository.reschedule(paymentId, micros(delay),
                Texts.abbreviate(lastError, Payment.MAX_LAST_ERROR)));
    }

    private static long micros(Duration duration) {
        return duration.toNanos() / 1_000;
    }

    private record Row(long paymentId, String orderId, LocalDateTime createdAt) {
    }

    // notFoundCancelDue는 집은 시각(DB) 기준으로 결제 생성 후 not-found-cancel-after가 지났는지다
    public record ClaimedPayment(long paymentId, String orderId, boolean notFoundCancelDue) {
    }
}
