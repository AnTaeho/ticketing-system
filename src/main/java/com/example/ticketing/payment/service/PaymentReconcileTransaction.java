package com.example.ticketing.payment.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.text.Texts;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.dto.ClaimedPayment;
import com.example.ticketing.payment.repository.PaymentRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기와 다음 확인 시각 갱신을 한 트랜잭션에서 한다. 커밋 뒤에는 다른 서버가 같은 결제를 다시 집지 않는다
@Component
public class PaymentReconcileTransaction {

    private final PaymentRepository paymentRepository;
    private final long notFoundCancelAfterNanos;
    // 한 건의 PG 조회가 끝나기 전에 다른 서버가 같은 결제를 집지 않게 미뤄 둔다
    private final long claimLeaseNanos;

    public PaymentReconcileTransaction(PaymentRepository paymentRepository, TicketingProperties properties) {
        this.paymentRepository = paymentRepository;
        this.notFoundCancelAfterNanos = properties.payment().notFoundCancelAfter().toNanos();
        this.claimLeaseNanos = properties.payment().reconcileClaimLease().toNanos();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedPayment> claim(int batch) {
        List<ClaimedPayment> claimed = paymentRepository.claimDue(notFoundCancelAfterNanos, Limit.of(batch));
        if (!claimed.isEmpty()) {
            paymentRepository.lease(claimed.stream().map(ClaimedPayment::paymentId).toList(), claimLeaseNanos);
        }
        return claimed;
    }

    // 끝난 결제는 건드리지 않는다. 바꿨으면 true
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean reschedule(long paymentId, Duration delay, String lastError) {
        return paymentRepository.reschedule(paymentId, TimeUnit.MICROSECONDS.convert(delay),
                Texts.abbreviate(lastError, Payment.MAX_LAST_ERROR)) == 1;
    }
}
