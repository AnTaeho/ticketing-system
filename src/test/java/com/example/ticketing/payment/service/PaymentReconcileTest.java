package com.example.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.hold.service.SeatHoldService;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.dto.ClaimedPayment;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.PaymentDb;
import com.example.ticketing.support.RedisKeyCleaner;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private PaymentReconcileTransaction reconcileTransaction;
    @Autowired
    private PaymentTransaction paymentTransaction;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private SeatGateReflector gate;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingRedis bookingRedis;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> performances = new ArrayList<>();
    private PaymentDb db;
    private StringRedisTemplate redis;
    private PaymentReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        db = new PaymentDb(jdbcTemplate);
        db.parkUnsettledPayments();
        redis = bookingRedis.template();
        scheduler = new PaymentReconcileScheduler(reconcileTransaction, paymentTransaction, stubPg, gate,
                properties, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
        performances.forEach(redisKeyCleaner::clean);
    }

    @Test
    @DisplayName("SLOW_SUCCESS → UNKNOWN → 대사(조회 APPROVED) → CONFIRMED: booking 1, 좌석 SOLD, 게이트 키 PTTL ≈ gate_close, "
            + "결제 next_check_at NULL")
    void unknownThenApprovedIsConfirmed() {
        Fixture f = newHold(2);
        long paymentId = unknownPayment(f);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-late-1"));
        double before = count("confirmed");
        db.makeDue(paymentId);

        scheduler.run();

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "APPROVED").containsEntry("pg_tx_id", "pg-late-1")
                .containsEntry("next_check_at", null);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("CONFIRMED");
        assertThat(db.bookingCountOfHold(f.holdId)).isEqualTo(1);
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "SOLD")
                .containsEntry("hold_id", f.holdId).containsEntry("held_until", null));
        long bookingId = jdbcTemplate.queryForObject("SELECT id FROM booking WHERE hold_id = ?", Long.class, f.holdId);
        assertThat(db.outboxCount("TICKET_ISSUED", bookingId)).isEqualTo(1);

        LocalDateTime gateCloseAt = jdbcTemplate.queryForObject(
                "SELECT gate_close_at FROM performance WHERE id = ?", LocalDateTime.class, f.pid);
        long expectedMs = Duration.between(db.dbNow(), gateCloseAt).toMillis();
        for (long seatId : f.seatIds) {
            String seatKey = RedisKeys.seat(f.pid, seatId);
            assertThat(redis.opsForValue().get(seatKey)).isEqualTo(f.holdToken);
            assertThat(pttl(seatKey)).isBetween(expectedMs - TOLERANCE_MS, expectedMs + TOLERANCE_MS);
        }
        assertThat(redis.hasKey(RedisKeys.userHold(f.userId, f.pid))).isFalse();
        assertThat(stubPg.queryCalls()).isEqualTo(1);
        assertThat(count("confirmed") - before).isEqualTo(1);
        System.out.printf("[S5.1] 대사 확정 좌석 키 PTTL=%dms, gate_close_at까지=%dms%n",
                pttl(RedisKeys.seat(f.pid, f.seatIds.get(0))), expectedMs);
    }

    @Test
    @DisplayName("REQUESTED 고아 + PG NOT_FOUND, 생성 30초 이내: 취소하지 않고 기다린다(next_check_at ≈ +10초)")
    void requestedOrphanWaitsBeforeCancelWindow() {
        Fixture f = newHold(1);
        long paymentId = orphan(f);
        double before = count("waiting");
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "REQUESTED");
        assertThat(db.recheckMs(paymentId)).isBetween(10_000L - TOLERANCE_MS, 10_000L);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("PAYING");
        assertThat(db.seat(f.seatIds.get(0))).containsEntry("status", "HELD");
        assertThat(stubPg.queryCalls()).isEqualTo(1);
        assertThat(stubPg.cancelCalls()).isZero();
        assertThat(count("waiting") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("REQUESTED 고아 + PG NOT_FOUND, 생성 30초 지남: 망취소 CANCELLED → 결제 CANCELLED, hold RELEASED, "
            + "좌석 AVAILABLE, 게이트 키 삭제")
    void requestedOrphanIsCancelledAfterWindow() {
        Fixture f = newHold(2);
        long paymentId = orphan(f);
        assertThat(redisKeyCleaner.gateKeys(f.pid)).isNotEmpty();
        double before = count("cancelled");
        db.ageCreatedAt(paymentId, 60);
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("RELEASED");
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null).containsEntry("held_until", null));
        assertThat(redisKeyCleaner.gateKeys(f.pid)).isEmpty();
        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(count("cancelled") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("REQUESTED 고아 망취소가 CONFLICT면 상태 그대로, last_error 기록, 다음 주기(next_check_at 미래)")
    void orphanCancelConflictWaitsForNextRound() {
        Fixture f = newHold(1);
        long paymentId = orphan(f);
        stubPg.cancelWith(PgResult.of(PgStatus.CONFLICT, null));
        db.ageCreatedAt(paymentId, 60);
        db.makeDue(paymentId);

        scheduler.run();

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "REQUESTED");
        assertThat((String) payment.get("last_error")).contains("CONFLICT");
        assertThat(db.recheckMs(paymentId)).isPositive();
        assertThat(db.holdStatus(f.holdId)).isEqualTo("PAYING");
        assertThat(db.seat(f.seatIds.get(0))).containsEntry("status", "HELD").containsEntry("hold_id", f.holdId);
        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(reconcileTransaction.claim(20)).extracting(ClaimedPayment::paymentId).doesNotContain(paymentId);
    }

    @Test
    @DisplayName("승인됐지만 좌석 상실: held_until 과거 → 남이 선점 → 대사가 APPROVED 확인 → hold REFUNDING, 남은 좌석 AVAILABLE, "
            + "outbox REFUND_REQUESTED 1, 결제 APPROVED")
    void approvedButSeatLost() {
        Fixture f = newHold(2);
        long kept = f.seatIds.get(0);
        long lost = f.seatIds.get(1);
        long paymentId = orphan(f);
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", lost);
        redis.delete(RedisKeys.seat(f.pid, lost));
        long thief = fixtures.newUser();
        CreateHoldResult thiefHold = seatHoldService.hold(
                new CreateHoldCommand(thief, f.pid, List.of(lost), fixtures.admit(f.pid, thief)));
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-lost-1"));
        double before = count("seat_lost");
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(db.holdStatus(f.holdId)).isEqualTo("REFUNDING");
        assertThat(db.seat(kept)).containsEntry("status", "AVAILABLE").containsEntry("hold_id", null);
        assertThat(db.seat(lost)).containsEntry("status", "HELD").containsEntry("hold_id", thiefHold.holdId());
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED").containsEntry("pg_tx_id", "pg-lost-1")
                .containsEntry("next_check_at", null);
        assertThat(db.outboxCount("REFUND_REQUESTED", paymentId)).isEqualTo(1);
        assertThat(db.bookingCountOfHold(f.holdId)).isZero();
        assertThat(redis.hasKey(RedisKeys.seat(f.pid, kept))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(f.userId, f.pid))).isFalse();
        assertThat(redis.opsForValue().get(RedisKeys.seat(f.pid, lost))).isEqualTo(db.holdToken(thiefHold.holdId()));
        assertThat(count("seat_lost") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("대사 2스레드 동시 run(): 한쪽만 집고(PG 조회 1회) 확정은 한 번, booking 1")
    void concurrentScansConfirmOnce() throws Exception {
        Fixture f = newHold(2);
        long paymentId = unknownPayment(f);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-race-1"));
        double before = count("confirmed");
        db.makeDue(paymentId);

        runTogether(2, scheduler::run);

        assertThat(stubPg.queryCalls()).isEqualTo(1);
        assertThat(db.bookingCountOfHold(f.holdId)).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED");
        assertThat(count("confirmed") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("집기는 SKIP LOCKED: 다른 트랜잭션이 잠근 결제는 기다리지 않고 건너뛰고, 락이 풀리면 집는다")
    void claimSkipsLockedRows() throws Exception {
        Fixture f = newHold(1);
        long paymentId = unknownPayment(f);
        db.makeDue(paymentId);
        LocalDateTime payDeadline = db.payDeadline(f.holdId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbcTemplate.queryForList("SELECT id FROM booking_payment WHERE id = ? FOR UPDATE", paymentId);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            List<ClaimedPayment> whileLocked = reconcileTransaction.claim(20);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(whileLocked).extracting(ClaimedPayment::paymentId).doesNotContain(paymentId);
            assertThat(elapsedMs).isLessThan(3_000);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        List<ClaimedPayment> afterUnlock = reconcileTransaction.claim(20);
        assertThat(afterUnlock).extracting(ClaimedPayment::paymentId).containsExactly(paymentId);
        ClaimedPayment claimed = afterUnlock.get(0);
        assertThat(claimed.orderId()).isEqualTo(db.payment(paymentId).get("order_id"));
        assertThat(db.payment(claimed.paymentId())).containsEntry("hold_id", f.holdId);
        assertThat(db.payDeadline(f.holdId)).isEqualTo(payDeadline);
        assertThat(claimed.notFoundCancelDue()).isFalse();
        assertThat(db.recheckMs(paymentId)).isBetween(30_000L - TOLERANCE_MS, 30_000L);
        assertThat(reconcileTransaction.claim(20)).isEmpty();
    }

    @Test
    @DisplayName("같은 집힌 건을 두 스레드가 동시에 대사해도 confirm은 한 번만 효과가 있다(booking 1, confirmed +1)")
    void sameClaimReconciledTwiceIsIdempotent() throws Exception {
        Fixture f = newHold(2);
        long paymentId = unknownPayment(f);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-twice-1"));
        double before = count("confirmed");
        db.makeDue(paymentId);
        List<ClaimedPayment> claimed = reconcileTransaction.claim(20);
        assertThat(claimed).extracting(ClaimedPayment::paymentId).containsExactly(paymentId);

        runTogether(2, () -> scheduler.reconcile(claimed.get(0)));

        assertThat(stubPg.queryCalls()).isEqualTo(2);
        assertThat(db.bookingCountOfHold(f.holdId)).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED");
        assertThat(db.holdStatus(f.holdId)).isEqualTo("CONFIRMED");
        assertThat(count("confirmed") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("조회 실패(UNKNOWN): next_check_at ≈ +10초, last_error 기록, 상태 그대로")
    void queryFailureReschedules() {
        Fixture f = newHold(1);
        long paymentId = unknownPayment(f);
        stubPg.queryWith(PgResult.unknown("connect timeout 500ms"));
        double before = count("query_failed");
        db.makeDue(paymentId);

        scheduler.run();

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "UNKNOWN");
        assertThat((String) payment.get("last_error")).contains("connect timeout 500ms");
        assertThat(db.recheckMs(paymentId)).isBetween(10_000L - TOLERANCE_MS, 10_000L);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("PAYING");
        assertThat(count("query_failed") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("PG가 아직 PROCESSING: next_check_at ≈ +5초, 상태 그대로")
    void processingRechecksSoon() {
        Fixture f = newHold(1);
        long paymentId = unknownPayment(f);
        stubPg.queryWith(PgResult.of(PgStatus.PROCESSING, null));
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "UNKNOWN");
        assertThat(db.recheckMs(paymentId)).isBetween(3_000L, 5_000L);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("PAYING");
    }

    @Test
    @DisplayName("PG가 DECLINED: 결제 DECLINED, hold RELEASED, 좌석 AVAILABLE, 게이트 키 삭제")
    void declinedReleasesSeats() {
        Fixture f = newHold(2);
        long paymentId = unknownPayment(f);
        stubPg.queryWith(PgResult.of(PgStatus.DECLINED, null));
        double before = count("declined");
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("RELEASED");
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(redisKeyCleaner.gateKeys(f.pid)).isEmpty();
        assertThat(count("declined") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("최종 상태 결제(APPROVED·DECLINED)는 next_check_at이 과거여도 집지 않는다")
    void settledPaymentsAreNotClaimed() {
        Fixture approved = newHold(1);
        long approvedId = paymentService.pay(new PayCommand(approved.userId, approved.holdId, key(),
                Optional.empty())).paymentId();
        Fixture declined = newHold(1);
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));
        long declinedId = paymentIdOfHold(declined.holdId, () ->
                paymentService.pay(new PayCommand(declined.userId, declined.holdId, key(), Optional.empty())));
        db.makeDue(approvedId);
        db.makeDue(declinedId);

        assertThat(reconcileTransaction.claim(20)).isEmpty();
        scheduler.run();

        assertThat(stubPg.queryCalls()).isZero();
        assertThat(db.payment(approvedId)).containsEntry("status", "APPROVED");
        assertThat(db.payment(declinedId)).containsEntry("status", "DECLINED");
        assertThat(reconcileTransaction.reschedule(approvedId, Duration.ofSeconds(5), "late")).isFalse();
        assertThat(db.payment(approvedId)).containsEntry("last_error", null);
    }

    @Test
    @DisplayName("REQUESTED 고아 + PG 조회 APPROVED(승인 뒤 요청 스레드가 멈춤) → 대사가 CONFIRMED: booking 1, 좌석 SOLD")
    void requestedOrphanApprovedIsConfirmed() {
        Fixture f = newHold(2);
        long paymentId = orphan(f);
        stubPg.queryWith(PgResult.of(PgStatus.APPROVED, "pg-orphan-ok"));
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("CONFIRMED");
        assertThat(db.bookingCountOfHold(f.holdId)).isEqualTo(1);
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "SOLD"));
    }

    @Test
    @DisplayName("REQUESTED 고아 + PG 조회 DECLINED → 결제 DECLINED, hold RELEASED, 좌석 AVAILABLE")
    void requestedOrphanDeclinedIsReleased() {
        Fixture f = newHold(1);
        long paymentId = orphan(f);
        stubPg.queryWith(PgResult.of(PgStatus.DECLINED, null));
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("RELEASED");
        assertThat(db.seat(f.seatIds.get(0))).containsEntry("status", "AVAILABLE").containsEntry("hold_id", null);
    }

    @Test
    @DisplayName("UNKNOWN + PG 조회 CANCELLED(망취소가 이미 끝남) → 결제 CANCELLED, hold RELEASED, 좌석 AVAILABLE, 게이트 키 삭제")
    void unknownCancelledAtPgIsReleased() {
        Fixture f = newHold(2);
        long paymentId = unknownPayment(f);
        stubPg.queryWith(PgResult.of(PgStatus.CANCELLED, null));
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("RELEASED");
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(redisKeyCleaner.gateKeys(f.pid)).isEmpty();
        assertThat(stubPg.cancelCalls()).isZero();
    }

    @Test
    @DisplayName("UNKNOWN + PG NOT_FOUND(요청이 PG에 닿지 않음), 생성 30초 지남 → 망취소 → 결제 CANCELLED, hold RELEASED, 좌석 AVAILABLE")
    void unknownNotFoundIsCancelledAfterWindow() {
        Fixture f = newHold(1);
        long paymentId = unknownPayment(f);
        db.ageCreatedAt(paymentId, 60);
        db.makeDue(paymentId);

        scheduler.run();

        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("RELEASED");
        assertThat(db.seat(f.seatIds.get(0))).containsEntry("status", "AVAILABLE").containsEntry("hold_id", null);
    }

    private Fixture newHold(int seatCount) {
        long pid = fixtures.newPerformance(1, 1, 6);
        performances.add(pid);
        long userId = fixtures.newUser();
        List<Long> seatIds = db.seatIds(pid).subList(0, seatCount);
        CreateHoldResult hold = seatHoldService.hold(
                new CreateHoldCommand(userId, pid, seatIds, fixtures.admit(pid, userId)));
        return new Fixture(pid, userId, hold.holdId(), List.copyOf(seatIds), db.holdToken(hold.holdId()));
    }

    // 승인 요청이 타임아웃으로 끝난 결제(UNKNOWN, 게이트 키는 결제 마감+1분)
    private long unknownPayment(Fixture f) {
        stubPg.approveWith(PgResult.unknown("read timeout 3s"));
        long paymentId = paymentService.pay(new PayCommand(f.userId, f.holdId, key(), Optional.empty())).paymentId();
        assertThat(db.payment(paymentId)).containsEntry("status", "UNKNOWN");
        return paymentId;
    }

    // 결제 시작만 커밋되고 PG를 부르기 전에 요청 스레드가 멈춘 결제(REQUESTED, PG에 주문 없음)
    private long orphan(Fixture f) {
        Started started = (Started) paymentTransaction.start(f.userId, f.holdId, key());
        assertThat(stubPg.approveCalls()).isZero();
        return started.paymentId();
    }

    private long paymentIdOfHold(long holdId, Runnable pay) {
        try {
            pay.run();
        } catch (RuntimeException ignored) {
            // 거절 응답(402)은 예외로 온다
        }
        return jdbcTemplate.queryForObject("SELECT id FROM booking_payment WHERE hold_id = ?", Long.class, holdId);
    }

    private double count(String result) {
        return meterRegistry.counter(Metrics.PAYMENT_RECONCILE, Tag.RESULT, result).count();
    }

    private long pttl(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        return ttl == null ? -3 : ttl;
    }

    private static void runTogether(int threads, Runnable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    action.run();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
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

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(long pid, long userId, long holdId, List<Long> seatIds, String holdToken) {
    }
}
