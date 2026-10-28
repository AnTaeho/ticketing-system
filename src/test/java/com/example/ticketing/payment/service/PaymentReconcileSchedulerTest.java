package com.example.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.payment.service.PaymentReconcileTransaction.ClaimedPayment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentReconcileSchedulerTest {

    private static final int BATCH = 20;
    private static final Duration RECHECK = Duration.ofSeconds(10);

    private PaymentReconcileTransaction reconcileTransaction;
    private PaymentTransaction paymentTransaction;
    private PgClient pgClient;
    private SeatGateRedisStore gate;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        reconcileTransaction = mock(PaymentReconcileTransaction.class);
        paymentTransaction = mock(PaymentTransaction.class);
        pgClient = mock(PgClient.class);
        gate = mock(SeatGateRedisStore.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("한 건이 예외를 던져도 루프가 멈추지 않고 다음 건을 처리한다")
    void oneFailureDoesNotStopLoop() {
        when(reconcileTransaction.claim(BATCH)).thenReturn(List.of(claimed(1, "o1", false), claimed(2, "o2", false)));
        when(pgClient.query("o1")).thenThrow(new IllegalStateException("boom"));
        when(pgClient.query("o2")).thenReturn(PgResult.of(PgStatus.APPROVED, "tx2"));
        when(paymentTransaction.confirm(2, "tx2")).thenReturn(applied(2, PaymentOutcome.CONFIRMED));

        scheduler(true).scan();

        verify(paymentTransaction).confirm(2, "tx2");
        verify(gate).confirmQuietly(eq(7L), eq(11L), eq(List.of(101L)), eq("token"), eq(Instant.EPOCH));
        assertThat(count("confirmed")).isEqualTo(1);
    }

    @Test
    @DisplayName("게이트 OFF면 대사가 결론을 내도 Redis 게이트를 부르지 않는다")
    void gateOffSkipsRedis() {
        when(reconcileTransaction.claim(BATCH)).thenReturn(List.of(claimed(1, "o1", false), claimed(2, "o2", false)));
        when(pgClient.query("o1")).thenReturn(PgResult.of(PgStatus.APPROVED, "tx1"));
        when(pgClient.query("o2")).thenReturn(PgResult.of(PgStatus.DECLINED, null));
        when(paymentTransaction.confirm(1, "tx1")).thenReturn(applied(1, PaymentOutcome.CONFIRMED));
        when(paymentTransaction.decline(2)).thenReturn(applied(2, PaymentOutcome.DECLINED));

        scheduler(false).scan();

        verifyNoInteractions(gate);
        assertThat(count("confirmed")).isEqualTo(1);
        assertThat(count("declined")).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 다른 쪽이 결론을 낸 결제(applied=false)는 Redis 반영도 카운트도 하지 않는다")
    void notAppliedIsNotReflected() {
        when(reconcileTransaction.claim(BATCH)).thenReturn(List.of(claimed(1, "o1", false)));
        when(pgClient.query("o1")).thenReturn(PgResult.of(PgStatus.APPROVED, "tx1"));
        when(paymentTransaction.confirm(1, "tx1")).thenReturn(settlement(1, PaymentOutcome.CONFIRMED, false));

        scheduler(true).scan();

        verifyNoInteractions(gate);
        assertThat(count("confirmed")).isZero();
    }

    @Test
    @DisplayName("NOT_FOUND + 생성 30초 지남 + 망취소가 DECLINED면 거절로 정리하고, 실패(UNKNOWN)면 last_error를 남기고 미룬다")
    void notFoundCancelOutcomes() {
        when(reconcileTransaction.claim(BATCH)).thenReturn(List.of(claimed(1, "o1", true), claimed(2, "o2", true)));
        when(pgClient.query(anyString())).thenReturn(PgResult.of(PgStatus.NOT_FOUND, null));
        when(pgClient.cancel("o1")).thenReturn(PgResult.of(PgStatus.DECLINED, null));
        when(pgClient.cancel("o2")).thenReturn(PgResult.unknown("connect refused"));
        when(paymentTransaction.decline(1)).thenReturn(applied(1, PaymentOutcome.DECLINED));

        scheduler(true).scan();

        verify(paymentTransaction).decline(1);
        verify(paymentTransaction, never()).cancelAndRelease(2);
        verify(reconcileTransaction).reschedule(2, RECHECK, "PG 취소 UNKNOWN: connect refused");
        assertThat(count("declined")).isEqualTo(1);
        assertThat(count("query_failed")).isEqualTo(1);
    }

    private PaymentReconcileScheduler scheduler(boolean gateEnabled) {
        TicketingProperties properties = new TicketingProperties(null, null, null,
                new TicketingProperties.Payment(Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(30),
                        Duration.ofSeconds(5), RECHECK, Duration.ofSeconds(30), true, 2000, BATCH,
                        Duration.ofSeconds(30)),
                new TicketingProperties.Gate(gateEnabled, 20), null, null, null, null, null, null);
        return new PaymentReconcileScheduler(reconcileTransaction, paymentTransaction, pgClient, gate, properties,
                meterRegistry);
    }

    private double count(String result) {
        return meterRegistry.counter(Metrics.PAYMENT_RECONCILE, Tag.RESULT, result).count();
    }

    private static ClaimedPayment claimed(long paymentId, String orderId, boolean cancelDue) {
        LocalDateTime now = LocalDateTime.now();
        return new ClaimedPayment(paymentId, paymentId + 100, orderId, PaymentStatus.UNKNOWN,
                now.minusSeconds(cancelDue ? 60 : 5), now.plusMinutes(10), cancelDue);
    }

    private static PaymentSettlement applied(long paymentId, PaymentOutcome outcome) {
        return settlement(paymentId, outcome, true);
    }

    private static PaymentSettlement settlement(long paymentId, PaymentOutcome outcome, boolean applied) {
        return new PaymentSettlement(paymentId, outcome, applied, PaymentStatus.APPROVED, HoldStatus.CONFIRMED,
                null, null, paymentId + 100, 7L, 11L, "token", applied ? List.of(101L) : List.of(),
                outcome == PaymentOutcome.CONFIRMED ? Instant.EPOCH : null);
    }
}
