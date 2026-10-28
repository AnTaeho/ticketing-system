package com.example.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.dto.HoldCreated;
import com.example.ticketing.hold.service.SeatHoldService;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResponse;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.PaymentDb;
import com.example.ticketing.support.RedisKeyCleaner;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(StubPgTestConfig.class)
class PaymentConcurrencyTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentTransaction paymentTransaction;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;

    private final List<Long> performances = new ArrayList<>();
    private PaymentDb db;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        db = new PaymentDb(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
        performances.forEach(redisKeyCleaner::clean);
    }

    @Test
    @DisplayName("같은 멱등키 10스레드(승인 지연 300ms): PG 승인 1회, 모든 응답의 paymentId 같음(202 PROCESSING 또는 200 CONFIRMED), "
            + "끝난 뒤 재요청은 200 CONFIRMED·같은 예매번호, booking 1행")
    void sameKeyTenThreads() throws Exception {
        long pid = newPerformance();
        long userId = fixtures.newUser();
        HoldCreated hold = seatHoldService.hold(userId, pid, db.seatIds(pid).subList(0, 2),
                fixtures.admit(pid, userId));
        stubPg.approveDelayMs(300);
        String key = UUID.randomUUID().toString();

        List<Outcome> outcomes = runConcurrently(10,
                i -> () -> paymentService.pay(userId, hold.holdId(), key, Optional.empty()));

        long processing = outcomes.stream().filter(o -> o.is(PaymentResponse.Status.PROCESSING)).count();
        long confirmed = outcomes.stream().filter(o -> o.is(PaymentResponse.Status.CONFIRMED)).count();
        System.out.printf("[S4.3] 같은 키 10스레드: 202 PROCESSING %d, 200 CONFIRMED %d, 기타 %s, PG 승인 호출 %d%n",
                processing, confirmed,
                outcomes.stream().filter(o -> o.response() == null).map(Outcome::describe).toList(),
                stubPg.approveCalls());

        assertThat(outcomes).allSatisfy(o -> assertThat(o.response()).isNotNull());
        assertThat(processing + confirmed).isEqualTo(10);
        assertThat(confirmed).isGreaterThanOrEqualTo(1);
        assertThat(outcomes.stream().map(o -> o.response().paymentId()).distinct()).hasSize(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);

        PaymentResponse after = paymentService.pay(userId, hold.holdId(), key, Optional.empty());
        PaymentResponse winner = outcomes.stream().filter(o -> o.is(PaymentResponse.Status.CONFIRMED))
                .findFirst().orElseThrow().response();
        assertThat(after.status()).isEqualTo(PaymentResponse.Status.CONFIRMED);
        assertThat(after.bookingNumber()).isEqualTo(winner.bookingNumber());
        assertThat(after.paymentId()).isEqualTo(winner.paymentId());
        assertThat(db.bookingCountOfHold(hold.holdId())).isEqualTo(1);
        assertThat(db.paymentCountOfHold(hold.holdId())).isEqualTo(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 멱등키 2스레드(탭 두 개): 결제 행 1, 하나만 결제되고 다른 하나는 409 ALREADY_PAYING")
    void differentKeysTwoTabs() throws Exception {
        long pid = newPerformance();
        long userId = fixtures.newUser();
        HoldCreated hold = seatHoldService.hold(userId, pid, db.seatIds(pid).subList(0, 2),
                fixtures.admit(pid, userId));
        stubPg.approveDelayMs(300);

        List<Outcome> outcomes = runConcurrently(2,
                i -> () -> paymentService.pay(userId, hold.holdId(), UUID.randomUUID().toString(), Optional.empty()));
        System.out.println("[S4.3] 다른 키 2스레드: " + outcomes.stream().map(Outcome::describe).toList());

        assertThat(outcomes).filteredOn(o -> o.response() != null).singleElement()
                .satisfies(o -> assertThat(o.response().status()).isEqualTo(PaymentResponse.Status.CONFIRMED));
        assertThat(outcomes).filteredOn(o -> o.error() != null).singleElement()
                .satisfies(o -> assertThat(o.error()).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ALREADY_PAYING)));
        assertThat(db.paymentCountOfHold(hold.holdId())).isEqualTo(1);
        assertThat(db.bookingCountOfHold(hold.holdId())).isEqualTo(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("confirm 멱등: 같은 paymentId로 2스레드 동시 + 1번 더 → 효과는 한 번, booking 1·booking_seat N·TICKET_ISSUED 1")
    void confirmIsIdempotent() throws Exception {
        long pid = newPerformance();
        long userId = fixtures.newUser();
        List<Long> seats = db.seatIds(pid).subList(0, 3);
        HoldCreated hold = seatHoldService.hold(userId, pid, seats, fixtures.admit(pid, userId));
        stubPg.approveWith(PgResult.unknown("timeout"));
        long paymentId = paymentService.pay(userId, hold.holdId(), UUID.randomUUID().toString(), Optional.empty())
                .paymentId();

        List<PaymentSettlement> settlements = new CopyOnWriteArrayList<>();
        List<Outcome> concurrent = runConcurrently(2, i -> () -> {
            settlements.add(paymentTransaction.confirm(paymentId, "pg-tx-" + paymentId));
            return null;
        });
        settlements.add(paymentTransaction.confirm(paymentId, "pg-tx-" + paymentId));
        System.out.println("[S4.3] confirm 3회 applied: " + settlements.stream().map(PaymentSettlement::applied).toList());

        assertThat(concurrent).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(settlements).hasSize(3)
                .allSatisfy(s -> assertThat(s.outcome()).isEqualTo(PaymentOutcome.CONFIRMED));
        assertThat(settlements).filteredOn(PaymentSettlement::applied).hasSize(1);
        assertThat(settlements.stream().map(PaymentSettlement::bookingId).distinct()).hasSize(1);
        long bookingId = settlements.get(0).bookingId();
        assertThat(db.bookingCountOfHold(hold.holdId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_seat WHERE booking_id = ?",
                Long.class, bookingId)).isEqualTo(3);
        assertThat(db.outboxCount("TICKET_ISSUED", bookingId)).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED")
                .containsEntry("pg_tx_id", "pg-tx-" + paymentId);
    }

    @Test
    @DisplayName("유니크 위반 안전망: 같은 사용자가 두 회차 hold를 같은 멱등키로 동시에 결제하면 "
            + "uk_payment_idem 위반 쪽은 먼저 만든 결제를 Existing으로 돌려받는다")
    void idempotencyUniqueViolationFallsBackToExisting() throws Exception {
        long userId = fixtures.newUser();
        long pidA = newPerformance();
        long pidB = newPerformance();
        HoldCreated holdA = seatHoldService.hold(userId, pidA, db.seatIds(pidA).subList(0, 1),
                fixtures.admit(pidA, userId));
        HoldCreated holdB = seatHoldService.hold(userId, pidB, db.seatIds(pidB).subList(0, 1),
                fixtures.admit(pidB, userId));
        stubPg.approveDelayMs(300);
        String key = UUID.randomUUID().toString();

        // 두 hold 행을 잠가 두 요청이 1단계(멱등키 조회)를 모두 지난 뒤 hold 락에서 기다리게 한다
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService blocker = Executors.newSingleThreadExecutor();
        Future<?> blocking = blocker.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            jdbcTemplate.queryForList("SELECT id FROM seat_hold WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                    Long.class, holdA.holdId(), holdB.holdId());
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        ExecutorService payers = Executors.newFixedThreadPool(2);
        Future<Outcome> payA = payers.submit(() -> attempt(
                () -> paymentService.pay(userId, holdA.holdId(), key, Optional.empty())));
        Future<Outcome> payB = payers.submit(() -> attempt(
                () -> paymentService.pay(userId, holdB.holdId(), key, Optional.empty())));
        boolean bothWaiting = awaitHoldLockWaits(holdA.holdId(), holdB.holdId());
        release.countDown();
        blocking.get(30, TimeUnit.SECONDS);
        List<Outcome> outcomes = List.of(payA.get(30, TimeUnit.SECONDS), payB.get(30, TimeUnit.SECONDS));
        payers.shutdownNow();
        blocker.shutdownNow();
        System.out.println("[S4.3] 같은 키·다른 hold 동시 결제(두 요청 모두 락 대기 확인=" + bothWaiting + "): "
                + outcomes.stream().map(Outcome::describe).toList());

        assertThat(bothWaiting).isTrue();
        assertThat(outcomes).allSatisfy(o -> assertThat(o.response()).isNotNull());
        assertThat(outcomes.stream().map(o -> o.response().paymentId()).distinct()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking_payment WHERE user_id = ? AND idempotency_key = ?",
                Long.class, userId, key)).isEqualTo(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
        long paidHold = jdbcTemplate.queryForObject(
                "SELECT hold_id FROM booking_payment WHERE user_id = ? AND idempotency_key = ?",
                Long.class, userId, key);
        long untouchedHold = paidHold == holdA.holdId() ? holdB.holdId() : holdA.holdId();
        assertThat(db.holdStatus(paidHold)).isEqualTo("CONFIRMED");
        assertThat(db.holdStatus(untouchedHold)).isEqualTo("HELD");
        assertThat(db.payDeadline(untouchedHold)).isNull();
        assertThat(db.paymentCountOfHold(untouchedHold)).isZero();
    }

    // 락을 기다리는 동안 InnoDB 트랜잭션 목록에는 안 보일 때가 있어 실행 중인 문장으로 확인한다
    private boolean awaitHoldLockWaits(long... holdIds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int waiting = 0;
            for (long holdId : holdIds) {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE info LIKE ?", Long.class,
                        "SELECT * FROM seat_hold WHERE id = " + holdId + " AND user_id = % FOR UPDATE");
                waiting += count == null ? 0 : count.intValue();
            }
            if (waiting >= holdIds.length) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private List<Outcome> runConcurrently(int threads, java.util.function.IntFunction<Callable<PaymentResponse>> task)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Outcome> outcomes = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<PaymentResponse> callable = task.apply(i);
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    outcomes.add(attempt(callable));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();
        assertThat(outcomes).hasSize(threads);
        return outcomes;
    }

    private static Outcome attempt(Callable<PaymentResponse> callable) {
        try {
            return new Outcome(callable.call(), null);
        } catch (Throwable e) {
            return new Outcome(null, e);
        }
    }

    private long newPerformance() {
        long pid = fixtures.newPerformance(1, 1, 4);
        performances.add(pid);
        return pid;
    }

    private record Outcome(PaymentResponse response, Throwable error) {

        boolean is(PaymentResponse.Status status) {
            return response != null && response.status() == status;
        }

        String describe() {
            if (response != null) {
                return response.status() + "#" + response.paymentId();
            }
            return error instanceof ApiException be ? be.getCode().name() : String.valueOf(error);
        }
    }
}
