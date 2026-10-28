package com.example.ticketing.payment.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.payment.service.PaymentReconcileTransaction.ClaimedPayment;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 좌석은 PG 결과가 확정된 뒤에만 푼다. 여러 서버가 동시에 돌아도 집기의 SKIP LOCKED로 나눠 갖는다
@Slf4j
@Component
@ConditionalOnProperty(name = "booking.payment.reconcile-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReconcileScheduler {

    static final Duration PROCESSING_RECHECK = Duration.ofSeconds(5);
    static final String METRIC = "booking.payment.reconcile";

    private final PaymentReconcileTransaction reconcileTransaction;
    private final PaymentTransaction paymentTransaction;
    private final PgClient pgClient;
    private final PaymentGateReflector reflector;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final Duration recheckAfter;

    public PaymentReconcileScheduler(PaymentReconcileTransaction reconcileTransaction,
                                     PaymentTransaction paymentTransaction, PgClient pgClient,
                                     SeatGateRedisStore gate, TicketingProperties properties, MeterRegistry meterRegistry) {
        this.reconcileTransaction = reconcileTransaction;
        this.paymentTransaction = paymentTransaction;
        this.pgClient = pgClient;
        this.reflector = new PaymentGateReflector(gate, properties.gate().enabled());
        this.meterRegistry = meterRegistry;
        this.batchSize = properties.payment().reconcileBatchSize();
        this.recheckAfter = properties.payment().unknownRecheckAfter();
    }

    @Scheduled(fixedDelayString = "${booking.payment.reconcile-scan-ms}")
    public void scan() {
        List<ClaimedPayment> claimed = reconcileTransaction.claim(batchSize);
        for (ClaimedPayment payment : claimed) {
            try {
                reconcile(payment);
            } catch (RuntimeException e) {
                // 집기에서 다음 확인 시각을 미뤄 두었으므로 이 건은 다음 주기에 다시 집힌다
                log.warn("[booking reconcile] 결제 {} 대사 실패, 다음 건으로 넘어간다", payment.paymentId(), e);
            }
        }
    }

    // 건마다 트랜잭션 밖에서 PG를 조회하고, 결론이 나면 트랜잭션 메서드가 커밋한 뒤 Redis에 반영한다
    void reconcile(ClaimedPayment payment) {
        PgResult pg = pgClient.query(payment.orderId());
        switch (pg.status()) {
            case APPROVED -> settle(paymentTransaction.confirm(payment.paymentId(), pg.pgTxId()));
            case DECLINED -> settle(paymentTransaction.decline(payment.paymentId()));
            case CANCELLED -> settle(paymentTransaction.cancelAndRelease(payment.paymentId()));
            case PROCESSING -> recheck(payment, PROCESSING_RECHECK, null, "waiting");
            case NOT_FOUND -> notFound(payment);
            default -> recheck(payment, recheckAfter, describe("PG 조회", pg), "query_failed");
        }
    }

    // 묘비 덕분에 망취소 뒤 늦게 온 승인은 거절되므로, 생성 후 일정 시간이 지나면 pay_deadline까지 기다리지 않는다
    private void notFound(ClaimedPayment payment) {
        if (!payment.notFoundCancelDue()) {
            recheck(payment, recheckAfter, null, "waiting");
            return;
        }
        PgResult cancel = pgClient.cancel(payment.orderId());
        switch (cancel.status()) {
            case CANCELLED -> settle(paymentTransaction.cancelAndRelease(payment.paymentId()));
            case DECLINED -> settle(paymentTransaction.decline(payment.paymentId()));
            case CONFLICT -> recheck(payment, recheckAfter, describe("PG 취소", cancel), "waiting");
            default -> recheck(payment, recheckAfter, describe("PG 취소", cancel), "query_failed");
        }
    }

    // 요청 스레드나 다른 서버가 먼저 결론을 냈으면 반영도 그쪽 몫이다
    private void settle(PaymentSettlement settlement) {
        if (!settlement.applied()) {
            log.debug("[booking reconcile] 결제 {}는 이미 {}", settlement.paymentId(), settlement.outcome());
            return;
        }
        reflector.reflect(settlement, settlement.seatIds());
        switch (settlement.outcome()) {
            case CONFIRMED -> count("confirmed");
            case SEAT_LOST -> count("seat_lost");
            case DECLINED -> count("declined");
            case CANCELLED -> count("cancelled");
            default -> {
            }
        }
        log.info("[booking reconcile] 결제 {} → {}", settlement.paymentId(), settlement.outcome());
    }

    private void recheck(ClaimedPayment payment, Duration delay, String lastError, String result) {
        reconcileTransaction.reschedule(payment.paymentId(), delay, lastError);
        count(result);
    }

    private void count(String result) {
        meterRegistry.counter(METRIC, "result", result).increment();
    }

    private static String describe(String action, PgResult result) {
        return action + " " + result.status() + (result.detail() != null ? ": " + result.detail() : "");
    }
}
