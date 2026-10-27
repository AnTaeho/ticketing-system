package com.example.ticketing.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.BookingProperties.Outbox;
import com.example.ticketing.booking.hold.SeatHoldService;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.outbox.domain.OutboxTaskType;
import com.example.ticketing.booking.outbox.handler.OutboxHandler;
import com.example.ticketing.booking.outbox.handler.RefundRequestedHandler;
import com.example.ticketing.booking.outbox.handler.TicketIssuedHandler;
import com.example.ticketing.booking.payment.BookingPaymentService;
import com.example.ticketing.booking.payment.BookingPaymentTransaction;
import com.example.ticketing.booking.payment.PaymentDb;
import com.example.ticketing.booking.payment.StartResult.Started;
import com.example.ticketing.booking.payment.StubPgClient;
import com.example.ticketing.booking.payment.StubPgTestConfig;
import com.example.ticketing.booking.payment.pg.PgResult;
import com.example.ticketing.booking.payment.pg.PgStatus;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// 워커 빈은 꺼져 있다. 같은 빈들로 워커를 직접 만들어 scan()을 부른다
@BookingIntegrationTest
@Import(StubPgTestConfig.class)
class OutboxWorkerTest {

    private static final long TOLERANCE_MS = 1_500;

    @Autowired
    private OutboxClaimTransaction claimTransaction;
    @Autowired
    private TicketIssuedHandler ticketIssuedHandler;
    @Autowired
    private RefundRequestedHandler refundRequestedHandler;
    @Autowired
    private BookingPaymentService paymentService;
    @Autowired
    private BookingPaymentTransaction paymentTransaction;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;

    private final List<Long> performances = new ArrayList<>();
    private PaymentDb db;
    private OutboxWorker worker;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        db = new PaymentDb(jdbcTemplate);
        parkUnfinishedTasks();
        worker = newWorker(List.of(ticketIssuedHandler, refundRequestedHandler), properties.outbox().batchSize());
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
        performances.forEach(redisKeyCleaner::clean);
    }

    @Test
    @DisplayName("notification·outbox_task 스키마: 예매당 알림 유니크, 사용자 인덱스, 300자 문구")
    void schema() {
        String notification = showCreateTable("notification");
        assertThat(notification)
                .contains("UNIQUE KEY `uk_notification_booking` (`booking_id`)")
                .contains("KEY `idx_notification_user` (`user_id`,`created_at`)")
                .contains("`message` varchar(300) NOT NULL")
                .contains("`created_at` datetime(6) NOT NULL");
        assertThat(showCreateTable("outbox_task"))
                .contains("KEY `idx_outbox_poll` (`status`,`next_attempt_at`)")
                .contains("`locked_until` datetime(6) DEFAULT NULL");
    }

    @Test
    @DisplayName("확정 → 워커 1회 → 발권 알림 1건(「공연」 회차 일시 첫 좌석 외 N석, 예매번호), outbox DONE")
    void ticketIssuedWritesNotification() {
        Confirmed c = confirmed(3);
        double before = count("TICKET_ISSUED", "done");

        worker.scan();

        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "DONE")
                .containsEntry("attempt", 0).containsEntry("locked_until", null);
        Map<String, Object> notification = jdbcTemplate.queryForMap(
                "SELECT user_id, message FROM notification WHERE booking_id = ?", c.bookingId);
        String message = (String) notification.get("message");
        System.out.println("[S5.2] 발권 알림 문구: " + message);
        assertThat(notification).containsEntry("user_id", c.userId);
        Map<String, Object> booking = jdbcTemplate.queryForMap("SELECT b.booking_number, s.title FROM booking b "
                + "JOIN performance p ON p.id = b.performance_id JOIN show_info s ON s.id = p.show_id "
                + "WHERE b.id = ?", c.bookingId);
        assertThat(message)
                .startsWith("「" + booking.get("title") + "」 ")
                .matches("「.+」 \\d{4}\\.\\d{1,2}\\.\\d{1,2}\\(.\\) \\d{2}:\\d{2} A구역 1열 1번 외 2석 예매가 확정됐어요\\. "
                        + "예매번호 B\\d{8}-\\d{6}")
                .endsWith("예매번호 " + booking.get("booking_number"));
        assertThat(count("TICKET_ISSUED", "done") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("좌석 1석이면 「외 N석」 없이 그 좌석만 적는다")
    void singleSeatHasNoOthers() {
        Confirmed c = confirmed(1);

        worker.scan();

        assertThat(notificationMessage(c.bookingId)).contains(" A구역 1열 1번 예매가 확정됐어요.").doesNotContain("외");
    }

    @Test
    @DisplayName("크래시 재현: 집기만 하고 멈춤(PROCESSING) → 잠금 중엔 안 집힘 → 처리 후 결과 반영 전 멈춤 → locked_until 지남 → "
            + "다시 처리, 알림 여전히 1건, DONE")
    void crashedTaskIsReprocessedOnce() {
        Confirmed c = confirmed(2);
        long taskId = taskId("TICKET_ISSUED", c.bookingId);
        List<ClaimedOutboxTask> claimed = claimTransaction.claim(50);
        assertThat(claimed).extracting(ClaimedOutboxTask::id).containsExactly(taskId);
        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "PROCESSING");

        worker.scan();
        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "PROCESSING");
        assertThat(notificationCount(c.bookingId)).isZero();

        // 핸들러는 끝났지만 DONE을 쓰기 전에 서버가 죽었다
        ticketIssuedHandler.handle(claimed.get(0));
        assertThat(notificationCount(c.bookingId)).isEqualTo(1);
        expireLock(taskId);

        worker.scan();

        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "DONE");
        assertThat(notificationCount(c.bookingId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 TICKET_ISSUED를 핸들러로 두 번 처리해도 알림 1건(두 번째는 예외 없이 성공)")
    void ticketIssuedIsIdempotent() {
        Confirmed c = confirmed(2);
        ClaimedOutboxTask task = claimTransaction.claim(50).get(0);

        ticketIssuedHandler.handle(task);
        String first = notificationMessage(c.bookingId);
        ticketIssuedHandler.handle(task);

        assertThat(notificationCount(c.bookingId)).isEqualTo(1);
        assertThat(notificationMessage(c.bookingId)).isEqualTo(first);
    }

    @Test
    @DisplayName("같은 TICKET_ISSUED를 5스레드가 동시에 처리해도 알림 1건, 유니크 위반은 성공으로 끝난다(예외 없음)")
    void concurrentSameTicketIssuedWritesOnce() throws Exception {
        Confirmed c = confirmed(2);
        ClaimedOutboxTask task = claimTransaction.claim(50).get(0);

        runTogether(5, () -> ticketIssuedHandler.handle(task));

        assertThat(notificationCount(c.bookingId)).isEqualTo(1);
    }

    @Test
    @DisplayName("잠금이 지나 다른 워커가 다시 집으면, 먼저 집은 쪽의 결과(DONE·실패)는 반영되지 않는다")
    void staleClaimCannotWriteResult() {
        Confirmed c = confirmed(1);
        long taskId = taskId("TICKET_ISSUED", c.bookingId);
        ClaimedOutboxTask stale = claimTransaction.claim(50).get(0);
        expireLock(taskId);
        ClaimedOutboxTask fresh = claimTransaction.claim(50).get(0);
        assertThat(fresh.id()).isEqualTo(taskId);
        assertThat(fresh.lockedUntil()).isAfter(stale.lockedUntil());

        assertThat(claimTransaction.markFailedAttempt(stale, "늦은 실패")).isEmpty();
        assertThat(claimTransaction.markDone(stale)).isFalse();
        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "PROCESSING")
                .containsEntry("attempt", 0).containsEntry("last_error", null);

        assertThat(claimTransaction.markDone(fresh)).isTrue();
        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "DONE");
        assertThat(claimTransaction.markDone(fresh)).isFalse();
    }

    @Test
    @DisplayName("환불: 좌석 상실로 생긴 REFUND_REQUESTED + PG 취소 CANCELLED → 결제 CANCELLED, hold RELEASED, outbox DONE")
    void refundCompletes() {
        Refund r = seatLostRefund();
        double before = count("REFUND_REQUESTED", "done");

        worker.scan();

        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(db.payment(r.paymentId)).containsEntry("status", "CANCELLED");
        assertThat(db.holdStatus(r.holdId)).isEqualTo("RELEASED");
        assertThat(task("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "DONE");
        assertThat(count("REFUND_REQUESTED", "done") - before).isEqualTo(1);

        // 다시 처리돼도(재전달) 바뀌는 행 없이 성공한다
        refundRequestedHandler.handle(new ClaimedOutboxTask(r.taskId, "REFUND_REQUESTED", r.paymentId,
                r.payload, 0, null));
        assertThat(db.payment(r.paymentId)).containsEntry("status", "CANCELLED");
        assertThat(db.holdStatus(r.holdId)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("환불 요청에 PG가 DECLINED(돈이 안 나감)로 답하면 결제 CANCELLED, hold RELEASED로 닫고 DONE")
    void refundDeclinedClosesPayment() {
        Refund r = seatLostRefund();
        stubPg.cancelWith(PgResult.of(PgStatus.DECLINED, null));

        worker.scan();

        assertThat(db.payment(r.paymentId)).containsEntry("status", "CANCELLED");
        assertThat(db.holdStatus(r.holdId)).isEqualTo("RELEASED");
        assertThat(task("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "DONE");
    }

    @Test
    @DisplayName("환불 PG CONFLICT → PENDING, attempt 1, next_attempt_at ≈ +2초, last_error. 계속 실패하면 2·4·8초 뒤 "
            + "attempt 5에서 FAILED")
    void refundConflictRetriesThenFails() {
        Refund r = seatLostRefund();
        stubPg.cancelWith(PgResult.of(PgStatus.CONFLICT, null));
        double retryBefore = count("REFUND_REQUESTED", "retry");
        double failedBefore = count("REFUND_REQUESTED", "failed");

        worker.scan();

        Map<String, Object> first = task("REFUND_REQUESTED", r.paymentId);
        assertThat(first).containsEntry("status", "PENDING").containsEntry("attempt", 1)
                .containsEntry("locked_until", null);
        assertThat((String) first.get("last_error")).contains("CONFLICT");
        assertThat(retryDelayMs(r.taskId)).isBetween(2_000L - TOLERANCE_MS, 2_000L);
        assertThat(db.payment(r.paymentId)).containsEntry("status", "APPROVED");
        assertThat(db.holdStatus(r.holdId)).isEqualTo("REFUNDING");

        worker.scan();
        assertThat(task("REFUND_REQUESTED", r.paymentId)).containsEntry("attempt", 1);

        for (int attempt = 2; attempt <= 4; attempt++) {
            makeDue(r.taskId);
            worker.scan();
            assertThat(task("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "PENDING")
                    .containsEntry("attempt", attempt);
            long expectedMs = (1L << attempt) * 1_000;
            assertThat(retryDelayMs(r.taskId)).isBetween(expectedMs - TOLERANCE_MS, expectedMs);
        }

        makeDue(r.taskId);
        worker.scan();

        assertThat(task("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "FAILED")
                .containsEntry("attempt", properties.outbox().maxAttempts()).containsEntry("locked_until", null);
        makeDue(r.taskId);
        worker.scan();
        assertThat(stubPg.cancelCalls()).isEqualTo(5);
        assertThat(count("REFUND_REQUESTED", "retry") - retryBefore).isEqualTo(4);
        assertThat(count("REFUND_REQUESTED", "failed") - failedBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("백오프는 2^attempt초, 최대 60초")
    void backoffIsCapped() {
        assertThat(OutboxClaimTransaction.backoffSeconds(1)).isEqualTo(2);
        assertThat(OutboxClaimTransaction.backoffSeconds(5)).isEqualTo(32);
        assertThat(OutboxClaimTransaction.backoffSeconds(6)).isEqualTo(60);
        assertThat(OutboxClaimTransaction.backoffSeconds(40)).isEqualTo(60);
    }

    @Test
    @DisplayName("같은 배치에서 한 작업의 핸들러가 예외여도 다른 작업은 DONE")
    void oneFailureDoesNotStopBatch() {
        Refund r = seatLostRefund();
        stubPg.cancelWith(PgResult.unknown("read timeout 3s"));
        Confirmed c = confirmed(1);
        assertThat(r.taskId).isLessThan(taskId("TICKET_ISSUED", c.bookingId));

        worker.scan();

        assertThat(task("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "PENDING")
                .containsEntry("attempt", 1);
        assertThat((String) task("REFUND_REQUESTED", r.paymentId).get("last_error")).contains("read timeout 3s");
        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "DONE");
        assertThat(notificationCount(c.bookingId)).isEqualTo(1);
    }

    @Test
    @DisplayName("핸들러가 없는 작업 종류는 실패로 기록하고(attempt 1, PENDING, last_error) 같은 배치의 다른 작업은 처리한다")
    void missingHandlerIsFailedAttempt() {
        Refund r = seatLostRefund();
        Confirmed c = confirmed(1);
        OutboxWorker ticketOnly = newWorker(List.of(ticketIssuedHandler), properties.outbox().batchSize());

        ticketOnly.scan();

        Map<String, Object> refund = task("REFUND_REQUESTED", r.paymentId);
        assertThat(refund).containsEntry("status", "PENDING").containsEntry("attempt", 1);
        assertThat((String) refund.get("last_error")).contains("핸들러 없음");
        assertThat(stubPg.cancelCalls()).isZero();
        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "DONE");
    }

    @Test
    @DisplayName("TICKET_ISSUED 30건을 워커 3스레드(배치 5)가 동시에 처리: 각 작업 핸들러 정확히 1회, 모두 DONE, 알림 30건")
    void concurrentWorkersProcessEachTaskOnce() throws Exception {
        int tasks = 30;
        List<Long> bookingIds = confirmedMany(tasks);
        Map<Long, AtomicInteger> handled = new ConcurrentHashMap<>();
        OutboxHandler counting = new OutboxHandler() {
            @Override
            public OutboxTaskType type() {
                return OutboxTaskType.TICKET_ISSUED;
            }

            @Override
            public void handle(ClaimedOutboxTask task) {
                handled.computeIfAbsent(task.id(), id -> new AtomicInteger()).incrementAndGet();
                ticketIssuedHandler.handle(task);
            }
        };
        OutboxWorker smallBatch = newWorker(List.of(counting, refundRequestedHandler), 5);
        double before = count("TICKET_ISSUED", "done");

        runTogether(3, () -> {
            for (int round = 0; round < 20 && pendingCount(bookingIds) > 0; round++) {
                smallBatch.scan();
            }
        });

        assertThat(handled).hasSize(tasks);
        assertThat(handled.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
        String ids = String.join(",", bookingIds.stream().map(String::valueOf).toList());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_task WHERE task_type = 'TICKET_ISSUED' "
                + "AND status = 'DONE' AND aggregate_id IN (" + ids + ")", Long.class)).isEqualTo(tasks);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE booking_id IN (" + ids + ")",
                Long.class)).isEqualTo(tasks);
        assertThat(count("TICKET_ISSUED", "done") - before).isEqualTo(tasks);
    }

    @Test
    @DisplayName("집기는 SKIP LOCKED: 같은 작업을 두 번 집지 않고, 집힌 작업은 잠금이 남아 있는 동안 다시 집히지 않는다")
    void claimDoesNotDoubleClaim() {
        Confirmed c = confirmed(1);
        long taskId = taskId("TICKET_ISSUED", c.bookingId);

        List<ClaimedOutboxTask> first = claimTransaction.claim(50);
        List<ClaimedOutboxTask> second = claimTransaction.claim(50);

        assertThat(first).extracting(ClaimedOutboxTask::id).containsExactly(taskId);
        assertThat(second).isEmpty();
        long lockMs = jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), locked_until) "
                + "FROM outbox_task WHERE id = ?", Long.class, taskId) / 1_000;
        long expectedMs = properties.outbox().lockDuration().toMillis();
        assertThat(lockMs).isBetween(expectedMs - TOLERANCE_MS, expectedMs);
        assertThat(claimTransaction.markDone(first.get(0))).isTrue();
        assertThat(claimTransaction.markFailedAttempt(first.get(0), "x")).isEmpty();
        assertThat(task("TICKET_ISSUED", c.bookingId)).containsEntry("status", "DONE")
                .containsEntry("attempt", 0).containsEntry("last_error", null);
    }

    private OutboxWorker newWorker(List<OutboxHandler> handlers, int batchSize) {
        Outbox outbox = properties.outbox();
        BookingProperties p = properties;
        BookingProperties withBatch = new BookingProperties(p.seed(), p.redis(), p.hold(), p.payment(), p.gate(),
                p.queue(), p.seatmap(), new Outbox(outbox.pollIntervalMs(), batchSize, outbox.lockDuration(),
                outbox.maxAttempts()), p.ops(), p.pg(), p.fakePg());
        return new OutboxWorker(claimTransaction, handlers, withBatch, meterRegistry);
    }

    // 결제 승인까지 마친 예매. 좌석은 A구역 1열 1번부터 seatCount석
    private Confirmed confirmed(int seatCount) {
        long pid = newPerformance(1, 1, 6);
        long userId = fixtures.newUser();
        List<Long> seatIds = db.seatIds(pid).subList(0, seatCount);
        HoldCreated hold = seatHoldService.hold(userId, pid, seatIds, null);
        paymentService.pay(userId, hold.holdId(), key(), Optional.empty());
        long bookingId = jdbcTemplate.queryForObject("SELECT id FROM booking WHERE hold_id = ?", Long.class,
                hold.holdId());
        return new Confirmed(userId, bookingId);
    }

    private List<Long> confirmedMany(int count) {
        long pid = newPerformance(1, 2, (count + 1) / 2);
        List<Long> seatIds = db.seatIds(pid);
        List<Long> bookingIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long userId = fixtures.newUser();
            HoldCreated hold = seatHoldService.hold(userId, pid, List.of(seatIds.get(i)), null);
            paymentService.pay(userId, hold.holdId(), key(), Optional.empty());
            bookingIds.add(jdbcTemplate.queryForObject("SELECT id FROM booking WHERE hold_id = ?", Long.class,
                    hold.holdId()));
        }
        return bookingIds;
    }

    // 결제 마감이 지나 한 좌석을 남이 가져간 뒤 승인이 확인된 결제(hold REFUNDING, 결제 APPROVED, REFUND_REQUESTED)
    private Refund seatLostRefund() {
        long pid = newPerformance(1, 1, 6);
        long userId = fixtures.newUser();
        List<Long> seatIds = db.seatIds(pid).subList(0, 2);
        HoldCreated hold = seatHoldService.hold(userId, pid, seatIds, null);
        long paymentId = ((Started) paymentTransaction.start(userId, hold.holdId(), key())).paymentId();
        long lost = seatIds.get(1);
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", lost);
        redisKeyCleaner.clean(pid);
        seatHoldService.hold(fixtures.newUser(), pid, List.of(lost), null);
        paymentTransaction.confirm(paymentId, "pg-refund-" + paymentId);
        assertThat(db.holdStatus(hold.holdId())).isEqualTo("REFUNDING");
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED");
        Map<String, Object> task = task("REFUND_REQUESTED", paymentId);
        assertThat(task).containsEntry("status", "PENDING");
        return new Refund(paymentId, hold.holdId(), taskId("REFUND_REQUESTED", paymentId), (String) task.get("payload"));
    }

    private long newPerformance(int sections, int rows, int seatsPerRow) {
        long pid = fixtures.newPerformance(sections, rows, seatsPerRow);
        performances.add(pid);
        return pid;
    }

    // 이전 테스트·실행이 남긴 미완료 작업을 먼 미래로 미뤄 이 테스트의 집기에 섞이지 않게 한다(테스트 전용 DB)
    private void parkUnfinishedTasks() {
        jdbcTemplate.update("UPDATE outbox_task SET next_attempt_at = '2999-12-31 00:00:00', "
                + "locked_until = IF(status = 'PROCESSING', '2999-12-31 00:00:00', locked_until) "
                + "WHERE status IN ('PENDING', 'PROCESSING')");
    }

    private Map<String, Object> task(String type, long aggregateId) {
        return jdbcTemplate.queryForMap("SELECT status, attempt, payload, last_error, next_attempt_at, locked_until "
                + "FROM outbox_task WHERE task_type = ? AND aggregate_id = ?", type, aggregateId);
    }

    private long taskId(String type, long aggregateId) {
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_task WHERE task_type = ? AND aggregate_id = ?",
                Long.class, type, aggregateId);
    }

    private void expireLock(long taskId) {
        jdbcTemplate.update("UPDATE outbox_task SET locked_until = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", taskId);
    }

    private void makeDue(long taskId) {
        jdbcTemplate.update("UPDATE outbox_task SET next_attempt_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?",
                taskId);
    }

    private long retryDelayMs(long taskId) {
        return jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), next_attempt_at) "
                + "FROM outbox_task WHERE id = ?", Long.class, taskId) / 1_000;
    }

    private long pendingCount(List<Long> bookingIds) {
        String ids = String.join(",", bookingIds.stream().map(String::valueOf).toList());
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_task WHERE task_type = 'TICKET_ISSUED' "
                + "AND status <> 'DONE' AND aggregate_id IN (" + ids + ")", Long.class);
    }

    private long notificationCount(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE booking_id = ?", Long.class,
                bookingId);
    }

    private String notificationMessage(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT message FROM notification WHERE booking_id = ?", String.class,
                bookingId);
    }

    private double count(String type, String result) {
        return meterRegistry.counter(OutboxWorker.METRIC, "type", type, "result", result).count();
    }

    private String showCreateTable(String table) {
        return jdbcTemplate.queryForObject("SHOW CREATE TABLE " + table, (rs, i) -> rs.getString(2));
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
                future.get(60, TimeUnit.SECONDS);
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

    private record Confirmed(long userId, long bookingId) {
    }

    private record Refund(long paymentId, long holdId, long taskId, String payload) {
    }
}
