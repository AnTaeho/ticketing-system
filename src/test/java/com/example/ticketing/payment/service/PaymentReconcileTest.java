package com.example.ticketing.payment.service;

import static com.example.ticketing.support.ApiErrors.catchApiException;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.dto.DuePayment;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Held;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 스케줄러 빈은 꺼져 있다. 같은 빈들로 스케줄러를 직접 만들어 run()을 부른다
@IntegrationTest
@Import(StubPgTestConfig.class)
class PaymentReconcileTest {

    private static final long TOLERANCE_MS = 5_000;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PaymentReader paymentReader;
    @Autowired
    private PaymentTransaction paymentTransaction;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private TestServices services;

    private StringRedisTemplate redis;
    private PaymentReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        db.parkUnsettledPayments();
        redis = testRedis.template();
        scheduler = services.paymentReconcileScheduler();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Unsettled.class)
    @DisplayName("결과 모름·고아 결제를 대사에서 PG가 승인했다고 하면 예매를 확정한다")
    void approvedAtPgIsConfirmed(Unsettled kind) {
        Held held = fixtures.held(2);
        long paymentId = unsettled(kind, held);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-late-1"));
        db.makePaymentDue(paymentId);

        scheduler.run();

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "APPROVED").containsEntry("pg_tx_id", "pg-late-1")
                .containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("CONFIRMED");
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "SOLD")
                .containsEntry("hold_id", held.holdId()));
        long bookingId = db.bookingIdOfHold(held.holdId());

        for (long seatId : held.seatIds()) {
            assertThat(redis.opsForValue().get(RedisKeys.seat(held.pid(), seatId))).isEqualTo(held.holdToken());
        }
        assertThat(stubPg.queryCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG에 주문이 없는 고아 결제가 생성 30초 안이면 취소하지 않고 다시 본다")
    void requestedOrphanWaitsBeforeCancelWindow() {
        Held held = fixtures.held(1);
        long paymentId = orphan(held);
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "REQUESTED");
        assertThat(db.paymentRecheckMs(paymentId)).isBetween(10_000L - TOLERANCE_MS, 10_000L);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("PAYING");
        assertThat(db.seat(held.seatIds().get(0))).containsEntry("status", "HELD");
        assertThat(stubPg.queryCalls()).isEqualTo(1);
        assertThat(stubPg.cancelCalls()).isZero();
    }

    @Test
    @DisplayName("결제 생성 시각은 넘겨받은 DB 시계 값으로 저장되고 망취소 판정이 그 값을 쓴다")
    void createdAtComesFromDbClock() {
        Held held = fixtures.held(1);
        DbClock clock60sEarlier = new DbClock(entityManager) {
            @Override
            public LocalDateTime now() {
                return super.now().minusSeconds(60);
            }
        };
        PaymentTransaction skewed = services.paymentTransaction(clock60sEarlier);
        LocalDateTime jvmNow = LocalDateTime.now();

        // new로 만든 객체에는 클래스의 @Transactional이 걸리지 않으므로 트랜잭션을 직접 연다
        Started started = (Started) new TransactionTemplate(transactionManager)
                .execute(status -> skewed.start(held.userId(), held.holdId(), key()));
        db.makePaymentDue(started.paymentId());

        assertThat(db.paymentCreatedAt(started.paymentId())).isBefore(jvmNow.minusSeconds(50));
        assertThat(paymentReader.findDue(1000)).filteredOn(due -> due.paymentId() == started.paymentId())
                .singleElement().satisfies(due -> assertThat(due.notFoundCancelDue()).isTrue());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Unsettled.class)
    @DisplayName("PG에 주문이 없는 결과 모름·고아 결제가 생성 30초를 넘으면 망취소하고 선점을 푼다")
    void notFoundAfterWindowIsCancelled(Unsettled kind) {
        Held held = fixtures.held(2);
        long paymentId = unsettled(kind, held);
        assertThat(testRedis.keys(held.pid())).isNotEmpty();
        db.agePayment(paymentId, 60);
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(testRedis.keys(held.pid())).isEmpty();
        assertThat(stubPg.cancelCalls()).isEqualTo(1);
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
        assertThat(paymentReader.findDue(20)).extracting(DuePayment::paymentId)
                .doesNotContain(paymentId);
    }

    @Test
    @DisplayName("집힌 같은 건을 두 스레드가 동시에 대사해도 확정은 한 번이다")
    void sameClaimReconciledTwiceIsIdempotent() {
        Held held = fixtures.held(2);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-twice-1"));
        db.makePaymentDue(paymentId);
        List<DuePayment> claimed = paymentReader.findDue(20);
        assertThat(claimed).extracting(DuePayment::paymentId).containsExactly(paymentId);

        Results<Void> results = Concurrently.of(2).readyWithin(Duration.ofSeconds(10)).within(Duration.ofSeconds(30))
                .runAll(i -> scheduler.reconcile(claimed.get(0)));

        assertThat(results.errors()).isEmpty();
        assertThat(stubPg.queryCalls()).isEqualTo(2);
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED");
        assertThat(db.holdStatus(held.holdId())).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PG 조회가 실패하면 상태를 두고 10초 뒤 다시 확인한다")
    void queryFailureReschedules() {
        Held held = fixtures.held(1);
        long paymentId = unknownPayment(held);
        stubPg.queryWith(PgResult.unknown("connect timeout 500ms"));
        db.makePaymentDue(paymentId);

        scheduler.run();

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "UNKNOWN");
        assertThat((String) payment.get("last_error")).contains("connect timeout 500ms");
        assertThat(db.paymentRecheckMs(paymentId)).isBetween(10_000L - TOLERANCE_MS, 10_000L);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("PAYING");
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
        db.makePaymentDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(testRedis.keys(held.pid())).isEmpty();
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

        assertThat(paymentReader.findDue(20)).isEmpty();
        scheduler.run();

        assertThat(stubPg.queryCalls()).isZero();
        assertThat(db.payment(approvedId)).containsEntry("status", "APPROVED");
        assertThat(db.payment(declinedId)).containsEntry("status", "DECLINED");
        assertThat(paymentTransaction.reschedule(approvedId, Duration.ofSeconds(5), "late")).isFalse();
        assertThat(db.payment(approvedId)).containsEntry("last_error", null);
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
        assertThat(testRedis.keys(held.pid())).isEmpty();
        assertThat(stubPg.cancelCalls()).isZero();
    }

    private long unsettled(Unsettled kind, Held held) {
        return kind == Unsettled.UNKNOWN ? unknownPayment(held) : orphan(held);
    }

    // 승인 요청이 타임아웃으로 끝난 결제(UNKNOWN)
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

    // 대사가 집는 아직 결론 없는 결제 두 갈래. 고아는 PG를 부르기 전에 멈춰 REQUESTED로 남은 결제다
    enum Unsettled {
        REQUESTED,
        UNKNOWN
    }
}
