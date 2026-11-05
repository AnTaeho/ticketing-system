package com.example.ticketing.payment.service;

import com.example.ticketing.common.text.Texts;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.repository.PaymentRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentReconcileTransaction {

    private final PaymentRepository paymentRepository;

    // 끝난 결제는 건드리지 않는다. 바꿨으면 true
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean reschedule(long paymentId, Duration delay, String lastError) {
        return paymentRepository.reschedule(paymentId, delay.toNanos(),
                Texts.abbreviate(lastError, Payment.MAX_LAST_ERROR)) == 1;
    }
}
