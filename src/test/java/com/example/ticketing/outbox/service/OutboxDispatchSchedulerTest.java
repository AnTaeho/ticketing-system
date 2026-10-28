package com.example.ticketing.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.notification.service.TicketIssuedHandler;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import com.example.ticketing.outbox.dto.RefundRequestedPayload;
import com.example.ticketing.outbox.dto.TicketIssuedPayload;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.payment.service.PaymentTransaction;
import com.example.ticketing.payment.service.RefundRequestedHandler;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Confirmed;
import com.example.ticketing.support.Fixtures.Held;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// 스케줄러 빈은 꺼져 있다. 같은 빈들로 스케줄러를 직접 만들어 run()을 부른다
@IntegrationTest
@Import(StubPgTestConfig.class)
class OutboxDispatchSchedulerTest {

    private static final long TOLERANCE_MS = 1_500;

    @Autowired
    private OutboxTaskTransaction outboxTaskTransaction;
    @Autowired
    private TicketIssuedHandler ticketIssuedHandler;
    @Autowired
    private RefundRequestedHandler refundRequestedHandler;
    @Autowired
    private PaymentTransaction paymentTransaction;
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
    @Autowired
    private Counters counters;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private JsonMapper jsonMapper;

    private OutboxDispatchScheduler worker;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        db.parkOutboxTasks();
        worker = services.outboxDispatchScheduler();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
    }

    @Test
    @DisplayName("예매가 확정되면 워커가 발권 알림 1건을 쓰고 작업을 DONE으로 닫는다")
    void ticketIssuedWritesNotification() {
        Confirmed c = fixtures.confirmed(3);
        double before = counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "TICKET_ISSUED", Tag.RESULT, "done");

        worker.run();

        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "DONE")
                .containsEntry("attempt", 0).containsEntry("locked_until", null);
        Map<String, Object> notification = jdbcTemplate.queryForMap(
                "SELECT user_id, message FROM notification WHERE booking_id = ?", c.bookingId());
        String message = (String) notification.get("message");
        assertThat(notification).containsEntry("user_id", c.userId());
        Map<String, Object> booking = jdbcTemplate.queryForMap("SELECT b.booking_number, s.title FROM booking b "
                + "JOIN performance p ON p.id = b.performance_id JOIN show_info s ON s.id = p.show_id "
                + "WHERE b.id = ?", c.bookingId());
        assertThat(message)
                .startsWith("「" + booking.get("title") + "」 ")
                .matches("「.+」 \\d{4}\\.\\d{1,2}\\.\\d{1,2}\\(.\\) \\d{2}:\\d{2} A구역 1열 1번 외 2석 예매가 확정됐어요\\. "
                        + "예매번호 B\\d{8}-\\d{6}")
                .endsWith("예매번호 " + booking.get("booking_number"));
        assertThat(counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "TICKET_ISSUED", Tag.RESULT, "done") - before)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("좌석이 1석이면 알림에 「외 N석」 없이 그 좌석만 적는다")
    void singleSeatHasNoOthers() {
        Confirmed c = fixtures.confirmed(1);

        worker.run();

        assertThat(notificationMessage(c.bookingId())).contains(" A구역 1열 1번 예매가 확정됐어요.").doesNotContain("외");
    }

    @Test
    @DisplayName("집기 → 처리 뒤 멈춤 → 잠금 만료 → 재처리 흐름에서 알림은 1건만 남는다")
    void crashedTaskIsReprocessedOnce() {
        Confirmed c = fixtures.confirmed(2);
        long taskId = db.outboxTaskId("TICKET_ISSUED", c.bookingId());
        List<ClaimedOutboxTask> claimed = outboxTaskTransaction.claim(50);
        assertThat(claimed).extracting(ClaimedOutboxTask::id).containsExactly(taskId);
        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "PROCESSING");

        worker.run();
        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "PROCESSING");
        assertThat(notificationCount(c.bookingId())).isZero();

        // 핸들러는 끝났지만 DONE을 쓰기 전에 서버가 죽었다
        ticketIssuedHandler.handle(payloadOf(claimed.get(0)));
        assertThat(notificationCount(c.bookingId())).isEqualTo(1);
        db.expireOutboxLock(taskId);

        worker.run();

        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "DONE");
        assertThat(notificationCount(c.bookingId())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 발권 작업을 핸들러로 두 번 처리해도 알림은 1건이다")
    void ticketIssuedIsIdempotent() {
        Confirmed c = fixtures.confirmed(2);
        TicketIssuedPayload payload = payloadOf(outboxTaskTransaction.claim(50).get(0));

        ticketIssuedHandler.handle(payload);
        String first = notificationMessage(c.bookingId());
        ticketIssuedHandler.handle(payload);

        assertThat(notificationCount(c.bookingId())).isEqualTo(1);
        assertThat(notificationMessage(c.bookingId())).isEqualTo(first);
    }

    @Test
    @DisplayName("같은 발권 작업을 5스레드가 동시에 처리해도 예외 없이 알림은 1건이다")
    void concurrentSameTicketIssuedWritesOnce() {
        Confirmed c = fixtures.confirmed(2);
        TicketIssuedPayload payload = payloadOf(outboxTaskTransaction.claim(50).get(0));

        Results<Void> results = Concurrently.of(5)
                .readyWithin(Duration.ofSeconds(10))
                .within(Duration.ofSeconds(60))
                .runAll(i -> ticketIssuedHandler.handle(payload));

        assertThat(results.errors()).isEmpty();
        assertThat(notificationCount(c.bookingId())).isEqualTo(1);
    }

    @Test
    @DisplayName("잠금이 지나 다시 집힌 작업은 먼저 집은 쪽이 결과를 쓰지 못한다")
    void staleClaimCannotWriteResult() {
        Confirmed c = fixtures.confirmed(1);
        long taskId = db.outboxTaskId("TICKET_ISSUED", c.bookingId());
        ClaimedOutboxTask stale = outboxTaskTransaction.claim(50).get(0);
        db.expireOutboxLock(taskId);
        ClaimedOutboxTask fresh = outboxTaskTransaction.claim(50).get(0);
        assertThat(fresh.id()).isEqualTo(taskId);
        assertThat(fresh.lockedUntil()).isAfter(stale.lockedUntil());

        assertThat(outboxTaskTransaction.markFailedAttempt(stale, "늦은 실패")).isEmpty();
        assertThat(outboxTaskTransaction.markDone(stale)).isFalse();
        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "PROCESSING")
                .containsEntry("attempt", 0).containsEntry("last_error", null);

        assertThat(outboxTaskTransaction.markDone(fresh)).isTrue();
        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "DONE");
        assertThat(outboxTaskTransaction.markDone(fresh)).isFalse();
    }

    @Test
    @DisplayName("좌석을 잃은 결제의 환불을 PG가 취소하면 결제가 CANCELLED로 닫힌다")
    void refundCompletes() {
        Refund r = seatLostRefund();
        double before = counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "REFUND_REQUESTED", Tag.RESULT, "done");

        worker.run();

        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(db.payment(r.paymentId)).containsEntry("status", "CANCELLED");
        assertThat(db.holdStatus(r.holdId)).isEqualTo("RELEASED");
        assertThat(db.outboxTask("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "DONE");
        assertThat(counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "REFUND_REQUESTED", Tag.RESULT, "done") - before)
                .isEqualTo(1);

        // 다시 처리돼도(재전달) 바뀌는 행 없이 성공한다
        refundRequestedHandler.handle(jsonMapper.readValue(r.payload, RefundRequestedPayload.class));
        assertThat(db.payment(r.paymentId)).containsEntry("status", "CANCELLED");
        assertThat(db.holdStatus(r.holdId)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("환불 요청에 PG가 DECLINED로 답해도 결제를 CANCELLED로 닫는다")
    void refundDeclinedClosesPayment() {
        Refund r = seatLostRefund();
        stubPg.cancelWith(PgResult.of(PgStatus.DECLINED, null));

        worker.run();

        assertThat(db.payment(r.paymentId)).containsEntry("status", "CANCELLED");
        assertThat(db.holdStatus(r.holdId)).isEqualTo("RELEASED");
        assertThat(db.outboxTask("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "DONE");
    }

    @Test
    @DisplayName("환불이 PG CONFLICT로 계속 실패하면 백오프로 재시도하다 다섯 번째에 FAILED가 된다")
    void refundConflictRetriesThenFails() {
        Refund r = seatLostRefund();
        stubPg.cancelWith(PgResult.of(PgStatus.CONFLICT, null));
        double retryBefore = counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "REFUND_REQUESTED", Tag.RESULT, "retry");
        double failedBefore = counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "REFUND_REQUESTED", Tag.RESULT, "failed");

        worker.run();

        Map<String, Object> first = db.outboxTask("REFUND_REQUESTED", r.paymentId);
        assertThat(first).containsEntry("status", "PENDING").containsEntry("attempt", 1)
                .containsEntry("locked_until", null);
        assertThat((String) first.get("last_error")).contains("CONFLICT");
        assertThat(db.outboxRetryDelayMs(r.taskId)).isBetween(2_000L - TOLERANCE_MS, 2_000L);
        assertThat(db.payment(r.paymentId)).containsEntry("status", "APPROVED");
        assertThat(db.holdStatus(r.holdId)).isEqualTo("REFUNDING");

        worker.run();
        assertThat(db.outboxTask("REFUND_REQUESTED", r.paymentId)).containsEntry("attempt", 1);

        for (int attempt = 2; attempt <= 4; attempt++) {
            db.makeOutboxDue(r.taskId);
            worker.run();
            assertThat(db.outboxTask("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "PENDING")
                    .containsEntry("attempt", attempt);
            long expectedMs = (1L << attempt) * 1_000;
            assertThat(db.outboxRetryDelayMs(r.taskId)).isBetween(expectedMs - TOLERANCE_MS, expectedMs);
        }

        db.makeOutboxDue(r.taskId);
        worker.run();

        assertThat(db.outboxTask("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "FAILED")
                .containsEntry("attempt", properties.outbox().maxAttempts()).containsEntry("locked_until", null);
        db.makeOutboxDue(r.taskId);
        worker.run();
        assertThat(stubPg.cancelCalls()).isEqualTo(5);
        assertThat(counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "REFUND_REQUESTED", Tag.RESULT, "retry")
                - retryBefore).isEqualTo(4);
        assertThat(counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "REFUND_REQUESTED", Tag.RESULT, "failed")
                - failedBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("백오프는 2^attempt초로 늘다가 max-backoff(60초)에서 멈춘다")
    void backoffIsCapped() {
        long max = properties.outbox().maxBackoff().toSeconds();

        assertThat(max).isEqualTo(60);
        assertThat(OutboxTaskTransaction.backoffSeconds(1, max)).isEqualTo(2);
        assertThat(OutboxTaskTransaction.backoffSeconds(5, max)).isEqualTo(32);
        assertThat(OutboxTaskTransaction.backoffSeconds(6, max)).isEqualTo(60);
        assertThat(OutboxTaskTransaction.backoffSeconds(40, max)).isEqualTo(60);
    }

    @Test
    @DisplayName("같은 배치에서 한 작업의 핸들러가 실패해도 다른 작업은 DONE이 된다")
    void oneFailureDoesNotStopBatch() {
        Refund r = seatLostRefund();
        stubPg.cancelWith(PgResult.unknown("read timeout 3s"));
        Confirmed c = fixtures.confirmed(1);
        assertThat(r.taskId).isLessThan(db.outboxTaskId("TICKET_ISSUED", c.bookingId()));

        worker.run();

        assertThat(db.outboxTask("REFUND_REQUESTED", r.paymentId)).containsEntry("status", "PENDING")
                .containsEntry("attempt", 1);
        assertThat((String) db.outboxTask("REFUND_REQUESTED", r.paymentId).get("last_error"))
                .contains("read timeout 3s");
        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "DONE");
        assertThat(notificationCount(c.bookingId())).isEqualTo(1);
    }

    @Test
    @DisplayName("핸들러가 없는 작업 종류는 실패로 기록하고 같은 배치의 다른 작업은 처리한다")
    void missingHandlerIsFailedAttempt() {
        Refund r = seatLostRefund();
        Confirmed c = fixtures.confirmed(1);
        OutboxDispatchScheduler ticketOnly = services.outboxDispatchScheduler(List.of(ticketIssuedHandler), properties);

        ticketOnly.run();

        Map<String, Object> refund = db.outboxTask("REFUND_REQUESTED", r.paymentId);
        assertThat(refund).containsEntry("status", "PENDING").containsEntry("attempt", 1);
        assertThat((String) refund.get("last_error")).contains("핸들러 없음");
        assertThat(stubPg.cancelCalls()).isZero();
        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "DONE");
    }

    @Test
    @DisplayName("발권 작업 30건을 워커 3스레드가 배치 5로 나눠 처리하면 작업마다 핸들러가 한 번 돈다")
    void concurrentWorkersProcessEachTaskOnce() {
        int tasks = 30;
        List<Long> bookingIds = confirmedMany(tasks);
        Map<Long, AtomicInteger> handled = new ConcurrentHashMap<>();
        // 예매 하나에 TICKET_ISSUED 작업 하나라 예매 ID로 작업을 센다
        OutboxHandler<TicketIssuedPayload> counting = new OutboxHandler<>() {
            @Override
            public OutboxTaskType type() {
                return OutboxTaskType.TICKET_ISSUED;
            }

            @Override
            public Class<TicketIssuedPayload> payloadType() {
                return TicketIssuedPayload.class;
            }

            @Override
            public void handle(TicketIssuedPayload payload) {
                handled.computeIfAbsent(payload.bookingId(), id -> new AtomicInteger()).incrementAndGet();
                ticketIssuedHandler.handle(payload);
            }
        };
        OutboxDispatchScheduler smallBatch = services.outboxDispatchScheduler(
                List.of(counting, refundRequestedHandler), TestProperties.from(properties).outboxBatchSize(5).build());
        double before = counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "TICKET_ISSUED", Tag.RESULT, "done");

        Results<Void> results = Concurrently.of(3)
                .readyWithin(Duration.ofSeconds(10))
                .within(Duration.ofSeconds(60))
                .runAll(i -> {
                    for (int round = 0; round < 20 && pendingCount(bookingIds) > 0; round++) {
                        smallBatch.run();
                    }
                });

        assertThat(results.errors()).isEmpty();
        assertThat(handled).hasSize(tasks);
        assertThat(handled.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
        String ids = String.join(",", bookingIds.stream().map(String::valueOf).toList());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_task WHERE task_type = 'TICKET_ISSUED' "
                + "AND status = 'DONE' AND aggregate_id IN (" + ids + ")", Long.class)).isEqualTo(tasks);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE booking_id IN (" + ids + ")",
                Long.class)).isEqualTo(tasks);
        assertThat(counters.count(Metrics.OUTBOX_TASK, Tag.TYPE, "TICKET_ISSUED", Tag.RESULT, "done") - before)
                .isEqualTo(tasks);
    }

    @Test
    @DisplayName("집힌 작업은 잠금이 남아 있는 동안 다시 집히지 않는다")
    void claimDoesNotDoubleClaim() {
        Confirmed c = fixtures.confirmed(1);
        long taskId = db.outboxTaskId("TICKET_ISSUED", c.bookingId());

        List<ClaimedOutboxTask> first = outboxTaskTransaction.claim(50);
        List<ClaimedOutboxTask> second = outboxTaskTransaction.claim(50);

        assertThat(first).extracting(ClaimedOutboxTask::id).containsExactly(taskId);
        assertThat(second).isEmpty();
        long lockMs = jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), locked_until) "
                + "FROM outbox_task WHERE id = ?", Long.class, taskId) / 1_000;
        long expectedMs = properties.outbox().lockDuration().toMillis();
        assertThat(lockMs).isBetween(expectedMs - TOLERANCE_MS, expectedMs);
        assertThat(outboxTaskTransaction.markDone(first.get(0))).isTrue();
        assertThat(outboxTaskTransaction.markFailedAttempt(first.get(0), "x")).isEmpty();
        assertThat(db.outboxTask("TICKET_ISSUED", c.bookingId())).containsEntry("status", "DONE")
                .containsEntry("attempt", 0).containsEntry("last_error", null);
    }

    // 한 회차(1구역 2열)에서 사용자 count명이 한 석씩 결제까지 마친 예매들
    private List<Long> confirmedMany(int count) {
        long pid = fixtures.newPerformance(1, 2, (count + 1) / 2);
        List<Long> seatIds = db.seatIds(pid);
        List<Long> bookingIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bookingIds.add(fixtures.confirmed(pid, List.of(seatIds.get(i))).bookingId());
        }
        return bookingIds;
    }

    // 결제 마감이 지나 한 좌석을 남이 가져간 뒤 승인이 확인된 결제(hold REFUNDING, 결제 APPROVED, REFUND_REQUESTED)
    private Refund seatLostRefund() {
        Held held = fixtures.held(2);
        Started started = (Started) paymentTransaction.start(held.userId(), held.holdId(), UUID.randomUUID().toString());
        long paymentId = started.paymentId();
        long lost = held.seatIds().get(1);
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", lost);
        testRedis.clean(held.pid());
        fixtures.held(held.pid(), List.of(lost));
        paymentTransaction.confirm(paymentId, "pg-refund-" + paymentId);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("REFUNDING");
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED");
        Map<String, Object> task = db.outboxTask("REFUND_REQUESTED", paymentId);
        assertThat(task).containsEntry("status", "PENDING");
        return new Refund(paymentId, held.holdId(), db.outboxTaskId("REFUND_REQUESTED", paymentId),
                (String) task.get("payload"));
    }

    private TicketIssuedPayload payloadOf(ClaimedOutboxTask task) {
        return jsonMapper.readValue(task.payload(), TicketIssuedPayload.class);
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

    private record Refund(long paymentId, long holdId, long taskId, String payload) {
    }
}
