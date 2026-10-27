package com.example.ticketing.booking.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.BookingRedis;
import com.example.ticketing.booking.hold.SeatHoldService;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.gate.BookingKeys;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.outbox.OutboxClaimTransaction;
import com.example.ticketing.booking.outbox.OutboxWorker;
import com.example.ticketing.booking.outbox.handler.RefundRequestedHandler;
import com.example.ticketing.booking.outbox.handler.TicketIssuedHandler;
import com.example.ticketing.booking.payment.BookingPaymentService;
import com.example.ticketing.booking.payment.PaymentDb;
import com.example.ticketing.booking.payment.StubPgClient;
import com.example.ticketing.booking.payment.StubPgTestConfig;
import com.example.ticketing.booking.payment.dto.PaymentResponse;
import com.example.ticketing.booking.payment.pg.PgResult;
import com.example.ticketing.booking.payment.pg.PgStatus;
import com.example.ticketing.booking.purchase.domain.BookingStatus;
import com.example.ticketing.booking.purchase.dto.BookingView;
import com.example.ticketing.booking.purchase.dto.CancelResponse;
import com.example.ticketing.booking.purchase.dto.RefundStatus;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@BookingIntegrationTest
@Import(StubPgTestConfig.class)
class BookingCancelTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingTransaction bookingTransaction;
    @Autowired
    private BookingViewReader viewReader;
    @Autowired
    private BookingPaymentService paymentService;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private OutboxClaimTransaction claimTransaction;
    @Autowired
    private TicketIssuedHandler ticketIssuedHandler;
    @Autowired
    private RefundRequestedHandler refundRequestedHandler;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BookingRedis bookingRedis;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;

    private final List<Long> performances = new ArrayList<>();
    private PaymentDb db;
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        db = new PaymentDb(jdbcTemplate);
        redis = bookingRedis.template();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
        performances.forEach(redisKeyCleaner::clean);
    }

    @Test
    @DisplayName("확정 예매 취소: booking CANCELLED·cancelled_at, 좌석 AVAILABLE·hold_id NULL, outbox REFUND_REQUESTED 1"
            + "({paymentId, orderId}), 좌석 게이트 키 삭제, 결제는 아직 APPROVED, hold CONFIRMED 그대로, "
            + "다른 사용자가 바로 그 좌석을 선점")
    void cancelConfirmedBooking() {
        Confirmed c = confirmed(2);
        c.seatIds.forEach(seatId -> assertThat(redis.opsForValue().get(BookingKeys.seat(c.pid, seatId)))
                .isEqualTo(c.holdToken));

        CancelResponse response = bookingService.cancel(c.userId, c.bookingId);

        assertThat(response).isEqualTo(new CancelResponse(c.bookingId, BookingStatus.CANCELLED, RefundStatus.REQUESTED));
        Map<String, Object> booking = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at FROM booking WHERE id = ?", c.bookingId);
        assertThat(booking).containsEntry("status", "CANCELLED");
        assertThat(booking.get("cancelled_at")).isNotNull();
        c.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null).containsEntry("held_until", null));
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId)).isEqualTo(1);
        Map<String, Object> task = db.outbox("REFUND_REQUESTED", c.paymentId);
        assertThat(task).containsEntry("status", "PENDING").containsEntry("attempt", 0);
        JsonNode payload = jsonMapper.readTree((String) task.get("payload"));
        assertThat(payload.get("paymentId").asLong()).isEqualTo(c.paymentId);
        assertThat(payload.get("orderId").asString()).isEqualTo(db.payment(c.paymentId).get("order_id"));
        c.seatIds.forEach(seatId -> assertThat(redis.hasKey(BookingKeys.seat(c.pid, seatId))).isFalse());
        assertThat(db.payment(c.paymentId)).containsEntry("status", "APPROVED");
        assertThat(db.holdStatus(c.holdId)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_seat WHERE booking_id = ?", Long.class,
                c.bookingId)).isEqualTo(2);

        long other = fixtures.newUser();
        HoldCreated retaken = seatHoldService.hold(other, c.pid, c.seatIds, null);

        c.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "HELD")
                .containsEntry("hold_id", retaken.holdId()));
        assertThat(redis.opsForValue().get(BookingKeys.seat(c.pid, c.seatIds.get(0))))
                .isEqualTo(db.holdToken(retaken.holdId()));
    }

    @Test
    @DisplayName("순차로 두 번 취소: 두 번째도 예외 없이 같은 응답, 환불 작업 1건. 그새 남이 잡은 좌석의 게이트 키를 지우지 않는다")
    void cancelTwiceSequentially() {
        Confirmed c = confirmed(1);

        CancelResponse first = bookingService.cancel(c.userId, c.bookingId);
        HoldCreated retaken = seatHoldService.hold(fixtures.newUser(), c.pid, c.seatIds, null);
        LocalDateTime cancelledAt = cancelledAt(c.bookingId);
        CancelResponse second = bookingService.cancel(c.userId, c.bookingId);

        assertThat(second).isEqualTo(first);
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId)).isEqualTo(1);
        assertThat(cancelledAt(c.bookingId)).isEqualTo(cancelledAt);
        assertThat(db.seat(c.seatIds.get(0))).containsEntry("status", "HELD")
                .containsEntry("hold_id", retaken.holdId());
        assertThat(redis.opsForValue().get(BookingKeys.seat(c.pid, c.seatIds.get(0))))
                .isEqualTo(db.holdToken(retaken.holdId()));
    }

    @Test
    @DisplayName("2스레드 동시 취소: 둘 다 예외 없이 같은 응답(CANCELLED·REQUESTED), 환불 작업 1건, 좌석 AVAILABLE")
    void cancelConcurrently() throws Exception {
        Confirmed c = confirmed(2);

        List<CancelResponse> responses = runTogether(2, () -> bookingService.cancel(c.userId, c.bookingId));

        assertThat(responses).hasSize(2).containsOnly(
                new CancelResponse(c.bookingId, BookingStatus.CANCELLED, RefundStatus.REQUESTED));
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId)).isEqualTo(1);
        c.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(redisKeyCleaner.keys(c.pid)).isEmpty();
    }

    @Test
    @DisplayName("회차 시작 후 취소: 409 CANCEL_NOT_ALLOWED. SeatCatalog 캐시는 아직 시작 전이어도 DB 시각으로 판정, 아무것도 바뀌지 않음")
    void cancelAfterStartIsRejected() {
        Confirmed c = confirmed(1);
        LocalDateTime cachedStartsAt = seatCatalog.performance(c.pid).startsAt();
        startPerformance(c.pid);
        assertThat(cachedStartsAt).isAfter(db.dbNow());
        assertThat(seatCatalog.performance(c.pid).startsAt()).isEqualTo(cachedStartsAt);

        BookingException e = catchBooking(() -> bookingService.cancel(c.userId, c.bookingId));

        assertThat(e.getCode()).isEqualTo(BookingErrorCode.CANCEL_NOT_ALLOWED);
        assertThat(jdbcTemplate.queryForMap("SELECT status, cancelled_at FROM booking WHERE id = ?", c.bookingId))
                .containsEntry("status", "CONFIRMED").containsEntry("cancelled_at", null);
        assertThat(db.seat(c.seatIds.get(0))).containsEntry("status", "SOLD").containsEntry("hold_id", c.holdId);
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId)).isZero();
        assertThat(redis.opsForValue().get(BookingKeys.seat(c.pid, c.seatIds.get(0)))).isEqualTo(c.holdToken);
        assertThat(bookingService.detail(c.userId, c.bookingId).cancellable()).isFalse();
    }

    @Test
    @DisplayName("이미 취소한 예매는 회차가 시작된 뒤에 다시 보내도 409가 아니라 현재 상태(200)를 돌려준다")
    void cancelledBookingStaysIdempotentAfterStart() {
        Confirmed c = confirmed(1);
        CancelResponse first = bookingService.cancel(c.userId, c.bookingId);
        startPerformance(c.pid);

        assertThat(bookingService.cancel(c.userId, c.bookingId)).isEqualTo(first);
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 예매 취소·조회와 없는 예매는 404 BOOKING_NOT_FOUND, 아무것도 바뀌지 않음")
    void othersBookingIsNotFound() {
        Confirmed c = confirmed(1);
        long stranger = fixtures.newUser();

        assertThat(catchBooking(() -> bookingService.cancel(stranger, c.bookingId)).getCode())
                .isEqualTo(BookingErrorCode.BOOKING_NOT_FOUND);
        assertThat(catchBooking(() -> bookingService.detail(stranger, c.bookingId)).getCode())
                .isEqualTo(BookingErrorCode.BOOKING_NOT_FOUND);
        assertThat(catchBooking(() -> bookingService.cancel(c.userId, Long.MAX_VALUE)).getCode())
                .isEqualTo(BookingErrorCode.BOOKING_NOT_FOUND);
        assertThat(bookingService.list(stranger)).isEmpty();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, c.bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(db.seat(c.seatIds.get(0))).containsEntry("status", "SOLD");
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId)).isZero();
    }

    @Test
    @DisplayName("취소 → 워커 1회(PG 취소 CANCELLED) → 결제 CANCELLED, outbox DONE, 상세 환불 DONE, hold는 CONFIRMED 그대로(D14)")
    void refundCompletesAfterCancel() {
        Confirmed c = confirmed(2);
        bookingService.cancel(c.userId, c.bookingId);
        assertThat(bookingService.detail(c.userId, c.bookingId).refund()).isEqualTo(RefundStatus.REQUESTED);
        parkOtherTasks(c);
        stubPg.cancelWith(PgResult.of(PgStatus.CANCELLED, null));
        OutboxWorker worker = new OutboxWorker(claimTransaction, List.of(ticketIssuedHandler, refundRequestedHandler),
                properties, meterRegistry);

        worker.scan();

        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(db.payment(c.paymentId)).containsEntry("status", "CANCELLED");
        assertThat(db.outbox("REFUND_REQUESTED", c.paymentId)).containsEntry("status", "DONE");
        assertThat(db.holdStatus(c.holdId)).isEqualTo("CONFIRMED");
        BookingView detail = bookingService.detail(c.userId, c.bookingId);
        assertThat(detail.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(detail.refund()).isEqualTo(RefundStatus.DONE);
        assertThat(detail.cancellable()).isFalse();
        assertThat(detail.seats()).hasSize(2);

        // 환불이 끝난 뒤 다시 취소를 보내도 바뀌는 것 없이 DONE을 돌려준다
        assertThat(bookingService.cancel(c.userId, c.bookingId))
                .isEqualTo(new CancelResponse(c.bookingId, BookingStatus.CANCELLED, RefundStatus.DONE));
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("게이트: OFF면 취소해도 Redis를 부르지 않고, ON이면 실제로 취소한 호출만 좌석 키를 지운다")
    void gateReflection() {
        RedisSeatGate offGate = mock(RedisSeatGate.class);
        BookingService gateOff = new BookingService(bookingTransaction, viewReader, offGate, withGate(false));
        Confirmed off = confirmed(1);
        gateOff.cancel(off.userId, off.bookingId);
        verifyNoInteractions(offGate);
        assertThat(db.seat(off.seatIds.get(0))).containsEntry("status", "AVAILABLE");

        RedisSeatGate onGate = mock(RedisSeatGate.class);
        BookingService gateOn = new BookingService(bookingTransaction, viewReader, onGate, withGate(true));
        Confirmed on = confirmed(2);
        gateOn.cancel(on.userId, on.bookingId);
        verify(onGate).releaseSeatsOnlyQuietly(on.pid, on.seatIds, on.holdToken);

        RedisSeatGate againGate = mock(RedisSeatGate.class);
        new BookingService(bookingTransaction, viewReader, againGate, withGate(true)).cancel(on.userId, on.bookingId);
        verify(againGate, never()).releaseSeatsOnlyQuietly(anyLong(), anyList(), anyString());
    }

    // 결제 승인까지 마친 예매. 좌석은 A구역 1열 1번부터 seatCount석
    private Confirmed confirmed(int seatCount) {
        long pid = fixtures.newPerformance(1, 1, 6);
        performances.add(pid);
        long userId = fixtures.newUser();
        List<Long> seatIds = db.seatIds(pid).subList(0, seatCount);
        HoldCreated hold = seatHoldService.hold(userId, pid, seatIds, null);
        PaymentResponse paid = paymentService.pay(userId, hold.holdId(), UUID.randomUUID().toString(),
                Optional.empty());
        assertThat(paid.status()).isEqualTo(PaymentResponse.Status.CONFIRMED);
        return new Confirmed(pid, userId, hold.holdId(), paid.paymentId(), paid.bookingId(), List.copyOf(seatIds),
                db.holdToken(hold.holdId()));
    }

    private void startPerformance(long pid) {
        jdbcTemplate.update("UPDATE performance SET starts_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", pid);
    }

    private LocalDateTime cancelledAt(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT cancelled_at FROM booking WHERE id = ?", LocalDateTime.class,
                bookingId);
    }

    // 이전 테스트·실행이 남긴 미완료 작업을 먼 미래로 미뤄 이 예매의 작업만 집히게 한다(테스트 전용 DB)
    private void parkOtherTasks(Confirmed c) {
        jdbcTemplate.update("UPDATE outbox_task SET next_attempt_at = '2999-12-31 00:00:00', "
                + "locked_until = IF(status = 'PROCESSING', '2999-12-31 00:00:00', locked_until) "
                + "WHERE status IN ('PENDING', 'PROCESSING') "
                + "AND NOT (task_type = 'REFUND_REQUESTED' AND aggregate_id = ?)", c.paymentId);
    }

    private BookingProperties withGate(boolean enabled) {
        BookingProperties p = properties;
        return new BookingProperties(p.seed(), p.redis(), p.hold(), p.payment(),
                new BookingProperties.Gate(enabled, p.gate().fallbackMaxConcurrency()), p.queue(), p.seatmap(),
                p.outbox(), p.ops(), p.pg(), p.fakePg());
    }

    private static BookingException catchBooking(Runnable action) {
        try {
            action.run();
        } catch (BookingException e) {
            return e;
        }
        throw new AssertionError("BookingException이 나야 한다");
    }

    private static <T> List<T> runTogether(int threads, Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("래치 대기 시간 초과");
                    }
                    return action.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private record Confirmed(long pid, long userId, long holdId, long paymentId, long bookingId, List<Long> seatIds,
                             String holdToken) {
    }
}
