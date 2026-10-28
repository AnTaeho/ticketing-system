package com.example.ticketing.payment.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.jdbc.RowCounts;
import com.example.ticketing.common.text.Texts;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.payment.domain.DuePayment;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.dto.ClaimedPayment;
import com.example.ticketing.payment.repository.PaymentJdbcRepository;
import com.example.ticketing.payment.repository.PaymentRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기와 다음 확인 시각 갱신을 한 트랜잭션에서 한다. 커밋 뒤에는 다른 서버가 같은 결제를 다시 집지 않는다
@Component
public class PaymentReconcileTransaction {

    private final PaymentJdbcRepository paymentJdbcRepository;
    private final PaymentRepository paymentRepository;
    private final DbClock dbClock;
    private final Duration notFoundCancelAfter;
    // 한 건의 PG 조회가 끝나기 전에 다른 서버가 같은 결제를 집지 않게 미뤄 둔다
    private final long claimLeaseMicros;

    public PaymentReconcileTransaction(PaymentJdbcRepository paymentJdbcRepository,
                                       PaymentRepository paymentRepository, DbClock dbClock,
                                       TicketingProperties properties) {
        this.paymentJdbcRepository = paymentJdbcRepository;
        this.paymentRepository = paymentRepository;
        this.dbClock = dbClock;
        this.notFoundCancelAfter = properties.payment().notFoundCancelAfter();
        this.claimLeaseMicros = micros(properties.payment().reconcileClaimLease());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedPayment> claim(int batch) {
        List<DuePayment> due = paymentJdbcRepository.claimDue(batch);
        if (due.isEmpty()) {
            return List.of();
        }
        paymentJdbcRepository.lease(due.stream().map(DuePayment::paymentId).toList(), claimLeaseMicros);

        LocalDateTime now = dbClock.now();
        return due.stream()
                .map(p -> new ClaimedPayment(p.paymentId(), p.orderId(),
                        !p.createdAt().plus(notFoundCancelAfter).isAfter(now)))
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
}
