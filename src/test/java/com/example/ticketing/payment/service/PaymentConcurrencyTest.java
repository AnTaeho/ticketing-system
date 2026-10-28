package com.example.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.support.Await;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Concurrently.Running;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Held;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import com.example.ticketing.support.TestDb;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        stubPg.reset();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
    }

    @Test
    @DisplayName("같은 멱등키로 10스레드가 동시에 결제해도 PG 승인은 한 번이고 결제도 하나다")
    void sameKeyTenThreads() {
        long pid = fixtures.newPerformance(1, 1, 4);
        Held held = fixtures.held(pid, db.seatIds(pid).subList(0, 2));
        stubPg.approveDelayMs(300);
        String key = UUID.randomUUID().toString();

        Results<PaymentResult> results = Concurrently.of(10).readyWithin(Duration.ofSeconds(10))
                .within(Duration.ofSeconds(60))
                .run(i -> paymentService.pay(new PayCommand(held.userId(), held.holdId(), key, Optional.empty())));

        assertThat(results.errors()).isEmpty();
        List<PaymentResult> responses = results.values();
        long processing = responses.stream().filter(r -> r.outcome() == PaymentOutcome.PROCESSING).count();
        long confirmed = responses.stream().filter(r -> r.outcome() == PaymentOutcome.CONFIRMED).count();
        assertThat(processing + confirmed).isEqualTo(10);
        assertThat(confirmed).isGreaterThanOrEqualTo(1);
        assertThat(responses.stream().map(PaymentResult::paymentId).distinct()).hasSize(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);

        PaymentResult after = paymentService.pay(
                new PayCommand(held.userId(), held.holdId(), key, Optional.empty()));

        PaymentResult winner = responses.stream().filter(r -> r.outcome() == PaymentOutcome.CONFIRMED)
                .findFirst().orElseThrow();
        assertThat(after.outcome()).isEqualTo(PaymentOutcome.CONFIRMED);
        assertThat(after.bookingNumber()).isEqualTo(winner.bookingNumber());
        assertThat(after.paymentId()).isEqualTo(winner.paymentId());
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(db.paymentCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("탭 두 개가 다른 멱등키로 동시에 결제하면 하나만 결제되고 다른 하나는 409다")
    void differentKeysTwoTabs() {
        long pid = fixtures.newPerformance(1, 1, 4);
        Held held = fixtures.held(pid, db.seatIds(pid).subList(0, 2));
        stubPg.approveDelayMs(300);

        Results<PaymentResult> results = Concurrently.of(2).readyWithin(Duration.ofSeconds(10))
                .within(Duration.ofSeconds(60))
                .run(i -> paymentService.pay(new PayCommand(held.userId(), held.holdId(), UUID.randomUUID().toString(),
                        Optional.empty())));

        assertThat(results.values()).singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo(PaymentOutcome.CONFIRMED));
        assertThat(results.errors()).singleElement()
                .satisfies(e -> assertThat(e).isInstanceOfSatisfying(ApiException.class,
                        api -> assertThat(api.getCode()).isEqualTo(ErrorCode.ALREADY_PAYING)));
        assertThat(db.paymentCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 결제를 동시에 두 번, 이어서 한 번 더 확정해도 효과는 한 번이다")
    void confirmIsIdempotent() {
        long pid = fixtures.newPerformance(1, 1, 4);
        Held held = fixtures.held(pid, db.seatIds(pid).subList(0, 3));
        stubPg.approveWith(PgResult.unknown("timeout"));
        long paymentId = paymentService.pay(new PayCommand(held.userId(), held.holdId(), UUID.randomUUID().toString(),
                Optional.empty())).paymentId();
        List<PaymentSettlement> settlements = new CopyOnWriteArrayList<>();

        Results<Void> concurrent = Concurrently.of(2).readyWithin(Duration.ofSeconds(10))
                .within(Duration.ofSeconds(60))
                .runAll(i -> settlements.add(paymentTransaction.confirm(paymentId, "pg-tx-" + paymentId)));
        settlements.add(paymentTransaction.confirm(paymentId, "pg-tx-" + paymentId));

        assertThat(concurrent.errors()).isEmpty();
        assertThat(settlements).hasSize(3)
                .allSatisfy(s -> assertThat(s.result().outcome()).isEqualTo(PaymentOutcome.CONFIRMED));
        assertThat(settlements).filteredOn(PaymentSettlement::changedSeats).hasSize(1);
        assertThat(settlements.stream().map(s -> s.result().bookingId()).distinct()).hasSize(1);
        long bookingId = settlements.get(0).result().bookingId();
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_seat WHERE booking_id = ?",
                Long.class, bookingId)).isEqualTo(3);
        assertThat(db.outboxCount("TICKET_ISSUED", bookingId)).isEqualTo(1);
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED")
                .containsEntry("pg_tx_id", "pg-tx-" + paymentId);
    }

    @Test
    @DisplayName("한 사용자가 두 선점을 같은 멱등키로 동시에 결제하면 늦은 쪽은 먼저 만든 결제를 돌려받는다")
    void idempotencyUniqueViolationFallsBackToExisting() throws Exception {
        long userId = fixtures.newUser();
        long pidA = fixtures.newPerformance(1, 1, 4);
        long pidB = fixtures.newPerformance(1, 1, 4);
        long holdA = fixtures.held(pidA, userId, db.seatIds(pidA).subList(0, 1)).holdId();
        long holdB = fixtures.held(pidB, userId, db.seatIds(pidB).subList(0, 1)).holdId();
        List<Long> holdIds = List.of(holdA, holdB);
        stubPg.approveDelayMs(300);
        String key = UUID.randomUUID().toString();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        boolean bothWaiting;
        Results<PaymentResult> results;

        // 두 hold 행을 잠가 두 요청이 1단계(멱등키 조회)를 모두 지난 뒤 hold 락에서 기다리게 한다
        try (Running<Void> blocker = Concurrently.of(1).within(Duration.ofSeconds(30)).start(i -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbcTemplate.queryForList("SELECT id FROM seat_hold WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                        Long.class, holdA, holdB);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return null;
        })) {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            try (Running<PaymentResult> payers = Concurrently.of(2).within(Duration.ofSeconds(30)).start(i ->
                    paymentService.pay(new PayCommand(userId, holdIds.get(i), key, Optional.empty())))) {
                bothWaiting = awaitHoldLockWaits(holdA, holdB);
                release.countDown();
                assertThat(blocker.await().errors()).isEmpty();
                results = payers.await();
            }
        } finally {
            release.countDown();
        }

        assertThat(bothWaiting).isTrue();
        assertThat(results.errors()).isEmpty();
        assertThat(results.values().stream().map(PaymentResult::paymentId).distinct()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking_payment WHERE user_id = ? AND idempotency_key = ?",
                Long.class, userId, key)).isEqualTo(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
        long paidHold = jdbcTemplate.queryForObject(
                "SELECT hold_id FROM booking_payment WHERE user_id = ? AND idempotency_key = ?",
                Long.class, userId, key);
        long untouchedHold = paidHold == holdA ? holdB : holdA;
        assertThat(db.holdStatus(paidHold)).isEqualTo("CONFIRMED");
        assertThat(db.holdStatus(untouchedHold)).isEqualTo("HELD");
        assertThat(db.payDeadline(untouchedHold)).isNull();
        assertThat(db.paymentCountOfHold(untouchedHold)).isZero();
    }

    // 락을 기다리는 동안 InnoDB 트랜잭션 목록에는 안 보일 때가 있어 실행 중인 문장으로 확인한다
    private boolean awaitHoldLockWaits(long... holdIds) {
        return Await.until(Duration.ofSeconds(10), Duration.ofMillis(20), () -> {
            int waiting = 0;
            for (long holdId : holdIds) {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE info LIKE ?", Long.class,
                        "SELECT * FROM seat_hold WHERE id = " + holdId + " AND user_id = % FOR UPDATE");
                waiting += count == null ? 0 : count.intValue();
            }
            return waiting >= holdIds.length;
        });
    }
}
