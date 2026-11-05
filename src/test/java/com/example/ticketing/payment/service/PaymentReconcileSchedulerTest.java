package com.example.ticketing.payment.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.config.TicketingProperties.Gate;
import com.example.ticketing.common.config.TicketingProperties.Hold;
import com.example.ticketing.common.config.TicketingProperties.Payment;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.payment.dto.DuePayment;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentReconcileSchedulerTest {

    private static final int BATCH = 20;
    private static final Duration RECHECK = Duration.ofSeconds(10);

    private PaymentReader paymentReader;
    private PaymentTransaction paymentTransaction;
    private PgClient pgClient;
    private SeatGateRedisStore seatGateRedisStore;

    @BeforeEach
    void setUp() {
        paymentReader = mock(PaymentReader.class);
        paymentTransaction = mock(PaymentTransaction.class);
        pgClient = mock(PgClient.class);
        seatGateRedisStore = mock(SeatGateRedisStore.class);
    }

    @Test
    @DisplayName("한 건이 예외를 던져도 루프가 멈추지 않고 다음 건을 처리한다")
    void oneFailureDoesNotStopLoop() {
        when(paymentReader.findDue(BATCH))
                .thenReturn(List.of(claimed(1, "o1", false), claimed(2, "o2", false)));
        when(pgClient.query("o1")).thenThrow(new IllegalStateException("boom"));
        when(pgClient.query("o2")).thenReturn(PgResult.of(PgStatus.APPROVED, "tx2"));
        when(paymentTransaction.openToApproved(2, "tx2")).thenReturn(seatsChanged(2, PaymentOutcome.CONFIRMED));

        scheduler(true).run();

        verify(paymentTransaction).openToApproved(2, "tx2");
        verifyNoInteractions(seatGateRedisStore);
    }

    @Test
    @DisplayName("게이트 OFF면 대사가 결론을 내도 Redis 게이트를 부르지 않는다")
    void gateOffSkipsRedis() {
        when(paymentReader.findDue(BATCH))
                .thenReturn(List.of(claimed(1, "o1", false), claimed(2, "o2", false)));
        when(pgClient.query("o1")).thenReturn(PgResult.of(PgStatus.APPROVED, "tx1"));
        when(pgClient.query("o2")).thenReturn(PgResult.of(PgStatus.DECLINED, null));
        when(paymentTransaction.openToApproved(1, "tx1")).thenReturn(seatsChanged(1, PaymentOutcome.CONFIRMED));
        when(paymentTransaction.openToDeclined(2)).thenReturn(seatsChanged(2, PaymentOutcome.DECLINED));

        scheduler(false).run();

        verifyNoInteractions(seatGateRedisStore);
    }

    @Test
    @DisplayName("대사가 거절로 결론을 내면 그 선점의 좌석 게이트 키를 지우고 확정은 키를 건드리지 않는다")
    void declinedReleasesGateKeys() {
        when(paymentReader.findDue(BATCH))
                .thenReturn(List.of(claimed(1, "o1", false), claimed(2, "o2", false)));
        when(pgClient.query("o1")).thenReturn(PgResult.of(PgStatus.DECLINED, null));
        when(pgClient.query("o2")).thenReturn(PgResult.of(PgStatus.APPROVED, "tx2"));
        when(paymentTransaction.openToDeclined(1)).thenReturn(seatsChanged(1, PaymentOutcome.DECLINED));
        when(paymentTransaction.openToApproved(2, "tx2")).thenReturn(seatsChanged(2, PaymentOutcome.CONFIRMED));

        scheduler(true).run();

        verify(seatGateRedisStore).release(new GateHold(7L, "token", List.of(101L)));
        verifyNoMoreInteractions(seatGateRedisStore);
    }

    @Test
    @DisplayName("다른 쪽이 먼저 결론을 내 좌석이 그대로면 Redis 반영도 하지 않는다")
    void notAppliedIsNotReflected() {
        when(paymentReader.findDue(BATCH)).thenReturn(List.of(claimed(1, "o1", false)));
        when(pgClient.query("o1")).thenReturn(PgResult.of(PgStatus.APPROVED, "tx1"));
        when(paymentTransaction.openToApproved(1, "tx1")).thenReturn(settlement(1, PaymentOutcome.CONFIRMED, false));

        scheduler(true).run();

        verifyNoInteractions(seatGateRedisStore);
    }

    @Test
    @DisplayName("PG에 없는 결제의 망취소가 거절이면 거절로 정리하고 실패면 오류를 남기고 미룬다")
    void notFoundCancelOutcomes() {
        when(paymentReader.findDue(BATCH))
                .thenReturn(List.of(claimed(1, "o1", true), claimed(2, "o2", true)));
        when(pgClient.query(anyString())).thenReturn(PgResult.of(PgStatus.NOT_FOUND, null));
        when(pgClient.cancel("o1")).thenReturn(PgResult.of(PgStatus.DECLINED, null));
        when(pgClient.cancel("o2")).thenReturn(PgResult.unknown("connect refused"));
        when(paymentTransaction.openToDeclined(1)).thenReturn(seatsChanged(1, PaymentOutcome.DECLINED));

        scheduler(true).run();

        verify(paymentTransaction).openToDeclined(1);
        verify(paymentTransaction, never()).openToCancelled(2);
        verify(paymentTransaction).reschedule(2, RECHECK, "PG 취소 UNKNOWN: connect refused");
    }

    private PaymentReconcileScheduler scheduler(boolean gateEnabled) {
        TicketingProperties properties = TestProperties.empty()
                .hold(new Hold(Duration.ofMinutes(5), 4, true, 1000, 50))
                .payment(new Payment(Duration.ofSeconds(30), Duration.ofSeconds(5), RECHECK,
                        Duration.ofSeconds(30), true, 2000, BATCH))
                .gate(new Gate(gateEnabled))
                .build();
        SeatGateReflector reflector = TestServices.gateReflector(seatGateRedisStore, properties);
        return TestServices.paymentReconcileScheduler(paymentReader, paymentTransaction, pgClient,
                reflector, properties);
    }

    private static DuePayment claimed(long paymentId, String orderId, boolean cancelDue) {
        return new DuePayment(paymentId, orderId, cancelDue);
    }

    private static PaymentSettlement seatsChanged(long paymentId, PaymentOutcome outcome) {
        return settlement(paymentId, outcome, true);
    }

    private static PaymentSettlement settlement(long paymentId, PaymentOutcome outcome, boolean changedSeats) {
        PaymentResult result = new PaymentResult(paymentId, outcome, PaymentStatus.APPROVED, HoldStatus.CONFIRMED,
                null, null);
        if (!changedSeats) {
            return PaymentSettlement.seatsUnchanged(result);
        }
        return PaymentSettlement.seatsChanged(result, new GateHold(7L, "token", List.of(101L)));
    }
}
