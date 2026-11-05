package com.example.ticketing.payment.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.dto.DuePayment;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentSettlement;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 좌석은 PG 결과가 확정된 뒤에만 푼다. 결제를 잠그지 않고 읽어 건마다 처리한다. 확정과 겹쳐도 hold 락 뒤 상태 확인으로 한 번만 효과가 있다
@Slf4j
@Component
@ConditionalOnProperty(name = "ticketing.payment.reconcile-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReconcileScheduler {

    private final PaymentReader paymentReader;
    private final PaymentReconcileTransaction reconcileTransaction;
    private final PaymentTransaction paymentTransaction;
    private final PgClient pgClient;
    private final SeatGateReflector gate;
    private final int batchSize;
    private final Duration recheckAfter;
    private final Duration processingRecheckAfter;

    public PaymentReconcileScheduler(PaymentReader paymentReader, PaymentReconcileTransaction reconcileTransaction,
                                     PaymentTransaction paymentTransaction, PgClient pgClient,
                                     SeatGateReflector gate, TicketingProperties properties) {
        this.paymentReader = paymentReader;
        this.reconcileTransaction = reconcileTransaction;
        this.paymentTransaction = paymentTransaction;
        this.pgClient = pgClient;
        this.gate = gate;
        this.batchSize = properties.payment().reconcileBatchSize();
        this.recheckAfter = properties.payment().unknownRecheckAfter();
        this.processingRecheckAfter = properties.payment().processingRecheckAfter();
    }

    @Scheduled(fixedDelayString = "${ticketing.payment.reconcile-scan-ms}")
    public void run() {
        List<DuePayment> claimed = paymentReader.findDue(batchSize);
        for (DuePayment payment : claimed) {
            try {
                reconcile(payment);
            } catch (RuntimeException e) {
                // 다음 확인 시각이 그대로라 이 건은 다음 주기에 다시 집힌다
                log.warn("[reconcile] 결제 대사 실패, 다음 건으로 넘어간다 paymentId={}", payment.paymentId(), e);
            }
        }
    }

    // 건마다 트랜잭션 밖에서 PG를 조회하고, 결론이 나면 트랜잭션 메서드가 커밋한 뒤 Redis에 반영한다
    void reconcile(DuePayment payment) {
        PgResult pg = pgClient.query(payment.orderId());
        switch (pg.status()) {
            case APPROVED -> settle(paymentTransaction.openToApproved(payment.paymentId(), pg.pgTxId()));
            case DECLINED -> settle(paymentTransaction.openToDeclined(payment.paymentId()));
            case CANCELLED -> settle(paymentTransaction.openToCancelled(payment.paymentId()));
            case PROCESSING -> recheck(payment, processingRecheckAfter, null);
            case NOT_FOUND -> notFound(payment);
            default -> recheck(payment, recheckAfter, pg.describe("PG 조회"));
        }
    }

    // 묘비 덕분에 망취소 뒤 늦게 온 승인은 거절되므로, 생성 후 일정 시간이 지나면 취소한다
    private void notFound(DuePayment payment) {
        if (!payment.notFoundCancelDue()) {
            recheck(payment, recheckAfter, null);
            return;
        }
        PgResult cancel = pgClient.cancel(payment.orderId());
        switch (cancel.status()) {
            case CANCELLED -> settle(paymentTransaction.openToCancelled(payment.paymentId()));
            case DECLINED -> settle(paymentTransaction.openToDeclined(payment.paymentId()));
            default -> recheck(payment, recheckAfter, cancel.describe("PG 취소"));
        }
    }

    // 요청 스레드가 먼저 결론을 냈으면 반영도 그쪽 몫이다
    private void settle(PaymentSettlement settlement) {
        PaymentResult result = settlement.result();
        if (!settlement.changedSeats()) {
            log.debug("[reconcile] 결제는 이미 결론이 났다 paymentId={} outcome={}", result.paymentId(), result.outcome());
            return;
        }
        settlement.gateToRelease().ifPresent(gate::release);
        log.info("[reconcile] 결제 결론을 반영했다 paymentId={} outcome={}", result.paymentId(), result.outcome());
    }

    private void recheck(DuePayment payment, Duration delay, String lastError) {
        reconcileTransaction.reschedule(payment.paymentId(), delay, lastError);
    }
}
