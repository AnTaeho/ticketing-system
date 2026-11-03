package com.example.ticketing.payment.repository;

import com.example.ticketing.payment.domain.DuePayment;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentJdbcRepository {

    private static final String CLAIM_DUE_SQL = """
            SELECT payment_id, order_id, created_at FROM booking_payment
             WHERE status IN ('REQUESTED', 'UNKNOWN') AND next_check_at <= NOW(6)
             ORDER BY payment_id LIMIT :batch
             FOR UPDATE SKIP LOCKED""";

    private static final String LEASE_SQL = """
            UPDATE booking_payment
               SET next_check_at = DATE_ADD(NOW(6), INTERVAL :leaseMicros MICROSECOND), updated_at = NOW(6)
             WHERE payment_id IN (:ids)""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 서버끼리 같은 행을 집으므로 미루기와 같은 트랜잭션에서만 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DuePayment> claimDue(int batch) {
        return jdbcTemplate.query(CLAIM_DUE_SQL, Map.of("batch", batch), new DataClassRowMapper<>(DuePayment.class));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lease(List<Long> paymentIds, long leaseMicros) {
        jdbcTemplate.update(LEASE_SQL, Map.of("ids", paymentIds, "leaseMicros", leaseMicros));
    }
}
