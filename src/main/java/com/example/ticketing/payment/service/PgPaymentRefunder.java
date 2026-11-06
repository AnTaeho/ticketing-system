package com.example.ticketing.payment.service;

import com.example.ticketing.booking.service.PaymentRefunder;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// PG 취소는 트랜잭션 밖에서 하고, 취소가 확정된 뒤에만 결제를 닫는다. 같은 orderId 취소는 PG가 같은 결과를 준다
@Slf4j
@Component
@RequiredArgsConstructor
public class PgPaymentRefunder implements PaymentRefunder {

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final PaymentTransaction paymentTransaction;

    @Override
    public boolean isRefunded(long paymentId) {
        return getPayment(paymentId).getStatus() == PaymentStatus.CANCELLED;
    }

    @Override
    public boolean refund(long paymentId) {
        String orderId = getPayment(paymentId).getOrderId();
        PgResult result = pgClient.cancel(orderId);
        switch (result.status()) {
            case CANCELLED, DECLINED -> {
                // DECLINED는 승인으로 확인했던 결제를 PG가 거절이라고 답한 경우다. 돈이 나가지 않은 쪽이라 같게 닫는다
                paymentTransaction.approvedToCancelled(paymentId);
                return true;
            }
            default -> {
                log.warn("[payment] 환불을 마치지 못했다 paymentId={} cause={}", paymentId, result.describe("PG 취소"));
                return false;
            }
        }
    }

    private Payment getPayment(long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("결제 " + paymentId + "가 없다"));
    }
}
