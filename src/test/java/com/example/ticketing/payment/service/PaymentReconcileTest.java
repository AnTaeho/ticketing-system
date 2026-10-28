package com.example.ticketing.payment.service;

import static com.example.ticketing.support.ApiErrors.catchApiException;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.service.SeatHoldService;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.dto.ClaimedPayment;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Concurrently.Running;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Held;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 스케줄러 빈은 꺼져 있다. 같은 빈들로 스케줄러를 직접 만들어 run()을 부른다
@IntegrationTest
@Import(StubPgTestConfig.class)
class PaymentReconcileTest {

    private static final long TOLERANCE_MS = 5_000;

    @Autowired
    private PaymentReconcileTransaction paymentReconcileTransaction;
    @Autowired
    private PaymentTransaction paymentTransaction;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestDb db;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private TestServices services;
    @Autowired
    private Counters counters;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private StringRedisTemplate redis;
    private PaymentReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        db.parkUnsettledPayments();
        redis = testRedis.template();
        scheduler = services.paymentReconcileScheduler();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
    }

    @Test
    @DisplayName("결과 모름 결제를 대사에서 PG가 승인했다고 하면 예매를 확정한다")
    void unknownThenApprovedIsConfirmed() {
        Held held = fixtures.held(2);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-late-1"));
        double before = counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "confirmed");
        db.makePaymentDue(paymentId);

        scheduler.run();

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "APPROVED").containsEntry("pg_tx_id", "pg-late-1")
                .containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("CONFIRMED");
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "SOLD")
                .containsEntry("hold_id", held.holdId()).containsEntry("held_until", null));
        long bookingId = db.bookingIdOfHold(held.holdId());
        assertThat(db.outboxCount("TICKET_ISSUED", bookingId)).isEqualTo(1);

        LocalDateTime gateCloseAt = jdbcTemplate.queryForObject(
                "SELECT gate_close_at FROM performance WHERE id = ?", LocalDateTime.class, held.pid());
        long expectedMs = Duration.between(db.dbNow(), gateCloseAt).toMillis();
        for (long seatId : held.seatIds()) {
            String seatKey = RedisKeys.seat(held.pid(), seatId);
            assertThat(redis.opsForValue().get(seatKey)).isEqualTo(held.holdToken());
            assertThat(testRedis.pttl(seatKey)).isBetween(expectedMs - TOLERANCE_MS, expectedMs + TOLERANCE_MS);
        }
        assertThat(redis.hasKey(RedisKeys.userHold(held.userId(), held.pid()))).isFalse();
        assertThat(stubPg.queryCalls()).isEqualTo(1);
        assertThat(counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "confirmed") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("PG에 주문이 없는 고아 결제가 생성 30초 안이면 취소하지 않고 다시 본다")
    void requestedOrphanWaitsBeforeCancelWindow() {
        Held held = fixtures.held(1);
        long paymentId = orphan(held);
        double before = counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "waiting");
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "REQUESTED");
        assertThat(db.paymentRecheckMs(paymentId)).isBetween(10_000L - TOLERANCE_MS, 10_000L);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("PAYING");
        assertThat(db.seat(held.seatIds().get(0))).containsEntry("status", "HELD");
        assertThat(stubPg.queryCalls()).isEqualTo(1);
        assertThat(stubPg.cancelCalls()).isZero();
        assertThat(counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "waiting") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("PG에 주문이 없는 고아 결제가 생성 30초를 넘으면 망취소하고 선점을 푼다")
    void requestedOrphanIsCancelledAfterWindow() {
        Held held = fixtures.held(2);
        long paymentId = orphan(held);
        assertThat(testRedis.gateKeys(held.pid())).isNotEmpty();
        double before = counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "cancelled");
        db.agePayment(paymentId, 60);
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null).containsEntry("held_until", null));
        assertThat(testRedis.gateKeys(held.pid())).isEmpty();
        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "cancelled") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("고아 결제 망취소가 CONFLICT면 상태를 두고 다음 주기에 다시 본다")
    void orphanCancelConflictWaitsForNextRound() {
        Held held = fixtures.held(1);
        long paymentId = orphan(held);
        stubPg.cancelWith(PgResult.of(PgStatus.CONFLICT, null));
        db.agePayment(paymentId, 60);
        db.makePaymentDue(paymentId);

        scheduler.run();

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "REQUESTED");
        assertThat((String) payment.get("last_error")).contains("CONFLICT");
        assertThat(db.paymentRecheckMs(paymentId)).isPositive();
        assertThat(db.holdStatus(held.holdId())).isEqualTo("PAYING");
        assertThat(db.seat(held.seatIds().get(0))).containsEntry("status", "HELD")
                .containsEntry("hold_id", held.holdId());
        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(paymentReconcileTransaction.claim(20)).extracting(ClaimedPayment::paymentId)
                .doesNotContain(paymentId);
    }

    @Test
    @DisplayName("대사에서 승인을 확인했는데 좌석을 잃었으면 환불로 넘긴다")
    void approvedButSeatLost() {
        Held held = fixtures.held(2);
        long kept = held.seatIds().get(0);
        long lost = held.seatIds().get(1);
        long paymentId = orphan(held);
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", lost);
        redis.delete(RedisKeys.seat(held.pid(), lost));
        long thief = fixtures.newUser();
        CreateHoldResult thiefHold = seatHoldService.hold(
                new CreateHoldCommand(thief, held.pid(), List.of(lost), fixtures.admit(held.pid(), thief)));
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-lost-1"));
        double before = counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "seat_lost");
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.holdStatus(held.holdId())).isEqualTo("REFUNDING");
        assertThat(db.seat(kept)).containsEntry("status", "AVAILABLE").containsEntry("hold_id", null);
        assertThat(db.seat(lost)).containsEntry("status", "HELD").containsEntry("hold_id", thiefHold.holdId());
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED").containsEntry("pg_tx_id", "pg-lost-1")
                .containsEntry("next_check_at", null);
        assertThat(db.outboxCount("REFUND_REQUESTED", paymentId)).isEqualTo(1);
        assertThat(db.bookingCountOfHold(held.holdId())).isZero();
        assertThat(redis.hasKey(RedisKeys.seat(held.pid(), kept))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(held.userId(), held.pid()))).isFalse();
        assertThat(redis.opsForValue().get(RedisKeys.seat(held.pid(), lost)))
                .isEqualTo(db.holdToken(thiefHold.holdId()));
        assertThat(counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "seat_lost") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("대사 두 개가 동시에 돌아도 한쪽만 집어 한 번만 확정한다")
    void concurrentScansConfirmOnce() {
        Held held = fixtures.held(2);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-race-1"));
        double before = counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "confirmed");
        db.makePaymentDue(paymentId);

        Results<Void> results = Concurrently.of(2).readyWithin(Duration.ofSeconds(10)).within(Duration.ofSeconds(30))
                .runAll(i -> scheduler.run());

        assertThat(results.errors()).isEmpty();
        assertThat(stubPg.queryCalls()).isEqualTo(1);
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED");
        assertThat(counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "confirmed") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 결제는 기다리지 않고 건너뛰고 락이 풀리면 집는다")
    void claimSkipsLockedRows() throws Exception {
        Held held = fixtures.held(1);
        long paymentId = unknownPayment(held);
        db.makePaymentDue(paymentId);
        LocalDateTime payDeadline = db.payDeadline(held.holdId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try (Running<Void> holder = Concurrently.of(1).within(Duration.ofSeconds(10)).start(i -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.queryForList("SELECT id FROM booking_payment WHERE id = ? FOR UPDATE", paymentId);
                locked.countDown();
                await(release);
            });
            return null;
        })) {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            List<ClaimedPayment> whileLocked = paymentReconcileTransaction.claim(20);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(whileLocked).extracting(ClaimedPayment::paymentId).doesNotContain(paymentId);
            assertThat(elapsedMs).isLessThan(3_000);
            release.countDown();
            assertThat(holder.await().errors()).isEmpty();
        } finally {
            release.countDown();
        }

        List<ClaimedPayment> afterUnlock = paymentReconcileTransaction.claim(20);
        assertThat(afterUnlock).extracting(ClaimedPayment::paymentId).containsExactly(paymentId);
        ClaimedPayment claimed = afterUnlock.get(0);
        assertThat(claimed.orderId()).isEqualTo(db.payment(paymentId).get("order_id"));
        assertThat(db.payment(claimed.paymentId())).containsEntry("hold_id", held.holdId());
        assertThat(db.payDeadline(held.holdId())).isEqualTo(payDeadline);
        assertThat(claimed.notFoundCancelDue()).isFalse();
        assertThat(db.paymentRecheckMs(paymentId)).isBetween(30_000L - TOLERANCE_MS, 30_000L);
        assertThat(paymentReconcileTransaction.claim(20)).isEmpty();
    }

    @Test
    @DisplayName("집힌 같은 건을 두 스레드가 동시에 대사해도 확정은 한 번이다")
    void sameClaimReconciledTwiceIsIdempotent() {
        Held held = fixtures.held(2);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-twice-1"));
        double before = counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "confirmed");
        db.makePaymentDue(paymentId);
        List<ClaimedPayment> claimed = paymentReconcileTransaction.claim(20);
        assertThat(claimed).extracting(ClaimedPayment::paymentId).containsExactly(paymentId);

        Results<Void> results = Concurrently.of(2).readyWithin(Duration.ofSeconds(10)).within(Duration.ofSeconds(30))
                .runAll(i -> scheduler.reconcile(claimed.get(0)));

        assertThat(results.errors()).isEmpty();
        assertThat(stubPg.queryCalls()).isEqualTo(2);
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED");
        assertThat(db.holdStatus(held.holdId())).isEqualTo("CONFIRMED");
        assertThat(counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "confirmed") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 조회가 실패하면 상태를 두고 10초 뒤 다시 확인한다")
    void queryFailureReschedules() {
        Held held = fixtures.held(1);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.unknown("connect timeout 500ms"));
        double before = counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "query_failed");
        db.makePaymentDue(paymentId);

        scheduler.run();

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "UNKNOWN");
        assertThat((String) payment.get("last_error")).contains("connect timeout 500ms");
        assertThat(db.paymentRecheckMs(paymentId)).isBetween(10_000L - TOLERANCE_MS, 10_000L);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("PAYING");
        assertThat(counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "query_failed") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("PG가 아직 처리 중이면 상태를 두고 5초 안에 다시 확인한다")
    void processingRechecksSoon() {
        Held held = fixtures.held(1);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.of(PgStatus.PROCESSING, null));
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "UNKNOWN");
        assertThat(db.paymentRecheckMs(paymentId)).isBetween(3_000L, 5_000L);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("PAYING");
    }

    @Test
    @DisplayName("대사에서 PG가 거절했다고 하면 선점을 풀고 게이트 키를 지운다")
    void declinedReleasesSeats() {
        Held held = fixtures.held(2);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.of(PgStatus.DECLINED, null));
        double before = counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "declined");
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(testRedis.gateKeys(held.pid())).isEmpty();
        assertThat(counters.count(Metrics.PAYMENT_RECONCILE, Tag.RESULT, "declined") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("끝난 결제는 확인 시각이 지나도 대사가 집지 않는다")
    void settledPaymentsAreNotClaimed() {
        Held approved = fixtures.held(1);
        long approvedId = paymentService.pay(command(approved)).paymentId();
        Held declined = fixtures.held(1);
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));
        ApiException declinedError = catchApiException(() -> paymentService.pay(command(declined)));
        long declinedId = ((Number) declinedError.getDetail().get("paymentId")).longValue();
        db.makePaymentDue(approvedId);
        db.makePaymentDue(declinedId);

        assertThat(paymentReconcileTransaction.claim(20)).isEmpty();
        scheduler.run();

        assertThat(stubPg.queryCalls()).isZero();
        assertThat(db.payment(approvedId)).containsEntry("status", "APPROVED");
        assertThat(db.payment(declinedId)).containsEntry("status", "DECLINED");
        assertThat(paymentReconcileTransaction.reschedule(approvedId, Duration.ofSeconds(5), "late")).isFalse();
        assertThat(db.payment(approvedId)).containsEntry("last_error", null);
    }

    @Test
    @DisplayName("고아 결제를 대사에서 PG가 승인했다고 하면 예매를 확정한다")
    void requestedOrphanApprovedIsConfirmed() {
        Held held = fixtures.held(2);
        long paymentId = orphan(held);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-orphan-ok"));
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("CONFIRMED");
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "SOLD"));
    }

    @Test
    @DisplayName("고아 결제를 대사에서 PG가 거절했다고 하면 선점을 푼다")
    void requestedOrphanDeclinedIsReleased() {
        Held held = fixtures.held(1);
        long paymentId = orphan(held);
        stubPg.queryWith(PgResult.of(PgStatus.DECLINED, null));
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        assertThat(db.seat(held.seatIds().get(0))).containsEntry("status", "AVAILABLE").containsEntry("hold_id", null);
    }

    @Test
    @DisplayName("PG에서 이미 취소된 결과 모름 결제는 망취소 없이 선점을 푼다")
    void unknownCancelledAtPgIsReleased() {
        Held held = fixtures.held(2);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.of(PgStatus.CANCELLED, null));
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(testRedis.gateKeys(held.pid())).isEmpty();
        assertThat(stubPg.cancelCalls()).isZero();
    }

    @Test
    @DisplayName("PG에 없는 결과 모름 결제가 생성 30초를 넘으면 망취소하고 선점을 푼다")
    void unknownNotFoundIsCancelledAfterWindow() {
        Held held = fixtures.held(1);
        long paymentId = unknownPayment(held);
        db.agePayment(paymentId, 60);
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        assertThat(db.seat(held.seatIds().get(0))).containsEntry("status", "AVAILABLE").containsEntry("hold_id", null);
    }

    // 승인 요청이 타임아웃으로 끝난 결제(UNKNOWN, 게이트 키는 결제 마감+1분)
    private long unknownPayment(Held held) {
        stubPg.approveWith(PgResult.unknown("read timeout 3s"));
        long paymentId = paymentService.pay(command(held)).paymentId();
        assertThat(db.payment(paymentId)).containsEntry("status", "UNKNOWN");
        return paymentId;
    }

    // 결제 시작만 커밋되고 PG를 부르기 전에 요청 스레드가 멈춘 결제(REQUESTED, PG에 주문 없음)
    private long orphan(Held held) {
        Started started = (Started) paymentTransaction.start(held.userId(), held.holdId(), key());
        assertThat(stubPg.approveCalls()).isZero();
        return started.paymentId();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static PayCommand command(Held held) {
        return new PayCommand(held.userId(), held.holdId(), key(), Optional.empty());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
