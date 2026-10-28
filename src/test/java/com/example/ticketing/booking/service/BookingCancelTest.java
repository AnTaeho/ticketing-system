package com.example.ticketing.booking.service;

import static com.example.ticketing.support.ApiErrors.catchApiException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.dto.BookingResult;
import com.example.ticketing.booking.dto.CancelResult;
import com.example.ticketing.booking.dto.RefundStatus;
import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
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
import java.time.LocalDateTime;
import java.util.Map;
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

@IntegrationTest
@Import(StubPgTestConfig.class)
class BookingCancelTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private SeatCatalog seatCatalog;
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private TicketingProperties properties;

    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        redis = testRedis.template();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
    }

    @Test
    @DisplayName("확정 예매를 취소하면 좌석이 풀리고 환불 작업이 쌓여 다른 사용자가 바로 잡을 수 있다")
    void cancelConfirmedBooking() {
        Confirmed c = fixtures.confirmed(2);
        c.seatIds().forEach(seatId -> assertThat(redis.opsForValue().get(RedisKeys.seat(c.pid(), seatId)))
                .isEqualTo(c.holdToken()));

        CancelResult response = bookingService.cancel(c.userId(), c.bookingId());

        assertThat(response)
                .isEqualTo(new CancelResult(c.bookingId(), BookingStatus.CANCELLED, RefundStatus.REQUESTED));
        Map<String, Object> booking = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at FROM booking WHERE id = ?", c.bookingId());
        assertThat(booking).containsEntry("status", "CANCELLED");
        assertThat(booking.get("cancelled_at")).isNotNull();
        c.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null).containsEntry("held_until", null));
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId())).isEqualTo(1);
        Map<String, Object> task = db.outboxTask("REFUND_REQUESTED", c.paymentId());
        assertThat(task).containsEntry("status", "PENDING").containsEntry("attempt", 0);
        JsonNode payload = jsonMapper.readTree((String) task.get("payload"));
        assertThat(payload.get("paymentId").asLong()).isEqualTo(c.paymentId());
        assertThat(payload.get("orderId").asString()).isEqualTo(db.payment(c.paymentId()).get("order_id"));
        c.seatIds().forEach(seatId -> assertThat(redis.hasKey(RedisKeys.seat(c.pid(), seatId))).isFalse());
        assertThat(db.payment(c.paymentId())).containsEntry("status", "APPROVED");
        assertThat(db.holdStatus(c.holdId())).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_seat WHERE booking_id = ?", Long.class,
                c.bookingId())).isEqualTo(2);

        Held retaken = fixtures.held(c.pid(), c.seatIds());

        c.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "HELD")
                .containsEntry("hold_id", retaken.holdId()));
        assertThat(redis.opsForValue().get(RedisKeys.seat(c.pid(), c.seatIds().get(0))))
                .isEqualTo(retaken.holdToken());
    }

    @Test
    @DisplayName("차례로 두 번 취소해도 같은 응답이고 그새 남이 잡은 좌석 키를 지우지 않는다")
    void cancelTwiceSequentially() {
        Confirmed c = fixtures.confirmed(1);

        CancelResult first = bookingService.cancel(c.userId(), c.bookingId());
        Held retaken = fixtures.held(c.pid(), c.seatIds());
        LocalDateTime cancelledAt = cancelledAt(c.bookingId());
        CancelResult second = bookingService.cancel(c.userId(), c.bookingId());

        assertThat(second).isEqualTo(first);
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId())).isEqualTo(1);
        assertThat(cancelledAt(c.bookingId())).isEqualTo(cancelledAt);
        assertThat(db.seat(c.seatIds().get(0))).containsEntry("status", "HELD")
                .containsEntry("hold_id", retaken.holdId());
        assertThat(redis.opsForValue().get(RedisKeys.seat(c.pid(), c.seatIds().get(0))))
                .isEqualTo(retaken.holdToken());
    }

    @Test
    @DisplayName("2스레드가 동시에 취소하면 둘 다 같은 응답을 받고 환불 작업은 1건이다")
    void cancelConcurrently() {
        Confirmed c = fixtures.confirmed(2);

        Results<CancelResult> results = Concurrently.of(2)
                .readyWithin(Duration.ofSeconds(10))
                .within(Duration.ofSeconds(30))
                .run(i -> bookingService.cancel(c.userId(), c.bookingId()));

        assertThat(results.errors()).isEmpty();
        assertThat(results.values()).hasSize(2).containsOnly(
                new CancelResult(c.bookingId(), BookingStatus.CANCELLED, RefundStatus.REQUESTED));
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId())).isEqualTo(1);
        c.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(testRedis.gateKeys(c.pid())).isEmpty();
    }

    @Test
    @DisplayName("회차가 시작된 뒤 취소하면 카탈로그 캐시와 상관없이 409로 거절한다")
    void cancelAfterStartIsRejected() {
        Confirmed c = fixtures.confirmed(1);
        LocalDateTime cachedStartsAt = seatCatalog.performance(c.pid()).startsAt();
        startPerformance(c.pid());
        assertThat(cachedStartsAt).isAfter(db.dbNow());
        assertThat(seatCatalog.performance(c.pid()).startsAt()).isEqualTo(cachedStartsAt);

        ApiException e = catchApiException(() -> bookingService.cancel(c.userId(), c.bookingId()));

        assertThat(e.getCode()).isEqualTo(ErrorCode.CANCEL_NOT_ALLOWED);
        assertThat(jdbcTemplate.queryForMap("SELECT status, cancelled_at FROM booking WHERE id = ?", c.bookingId()))
                .containsEntry("status", "CONFIRMED").containsEntry("cancelled_at", null);
        assertThat(db.seat(c.seatIds().get(0))).containsEntry("status", "SOLD").containsEntry("hold_id", c.holdId());
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId())).isZero();
        assertThat(redis.opsForValue().get(RedisKeys.seat(c.pid(), c.seatIds().get(0)))).isEqualTo(c.holdToken());
        assertThat(bookingService.detail(c.userId(), c.bookingId()).cancellable()).isFalse();
    }

    @Test
    @DisplayName("이미 취소한 예매는 회차가 시작된 뒤 다시 보내도 현재 상태를 돌려준다")
    void cancelledBookingStaysIdempotentAfterStart() {
        Confirmed c = fixtures.confirmed(1);
        CancelResult first = bookingService.cancel(c.userId(), c.bookingId());
        startPerformance(c.pid());

        assertThat(bookingService.cancel(c.userId(), c.bookingId())).isEqualTo(first);
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 예매나 없는 예매를 취소·조회하면 404로 답하고 아무것도 바꾸지 않는다")
    void othersBookingIsNotFound() {
        Confirmed c = fixtures.confirmed(1);
        long stranger = fixtures.newUser();

        assertThat(catchApiException(() -> bookingService.cancel(stranger, c.bookingId())).getCode())
                .isEqualTo(ErrorCode.BOOKING_NOT_FOUND);
        assertThat(catchApiException(() -> bookingService.detail(stranger, c.bookingId())).getCode())
                .isEqualTo(ErrorCode.BOOKING_NOT_FOUND);
        assertThat(catchApiException(() -> bookingService.cancel(c.userId(), Long.MAX_VALUE)).getCode())
                .isEqualTo(ErrorCode.BOOKING_NOT_FOUND);
        assertThat(bookingService.list(stranger)).isEmpty();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, c.bookingId()))
                .isEqualTo("CONFIRMED");
        assertThat(db.seat(c.seatIds().get(0))).containsEntry("status", "SOLD");
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId())).isZero();
    }

    @Test
    @DisplayName("취소 → 환불 워커 처리 흐름에서 결제가 CANCELLED, 환불이 DONE이 된다")
    void refundCompletesAfterCancel() {
        Confirmed c = fixtures.confirmed(2);
        bookingService.cancel(c.userId(), c.bookingId());
        assertThat(bookingService.detail(c.userId(), c.bookingId()).refund()).isEqualTo(RefundStatus.REQUESTED);
        db.parkOutboxTasksExcept("REFUND_REQUESTED", c.paymentId());
        stubPg.cancelWith(PgResult.of(PgStatus.CANCELLED, null));

        services.outboxDispatchScheduler().run();

        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(db.payment(c.paymentId())).containsEntry("status", "CANCELLED");
        assertThat(db.outboxTask("REFUND_REQUESTED", c.paymentId())).containsEntry("status", "DONE");
        assertThat(db.holdStatus(c.holdId())).isEqualTo("CONFIRMED");
        BookingResult detail = bookingService.detail(c.userId(), c.bookingId());
        assertThat(detail.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(detail.refund()).isEqualTo(RefundStatus.DONE);
        assertThat(detail.cancellable()).isFalse();
        assertThat(detail.seats()).hasSize(2);

        // 환불이 끝난 뒤 다시 취소를 보내도 바뀌는 것 없이 DONE을 돌려준다
        assertThat(bookingService.cancel(c.userId(), c.bookingId()))
                .isEqualTo(new CancelResult(c.bookingId(), BookingStatus.CANCELLED, RefundStatus.DONE));
        assertThat(db.outboxCount("REFUND_REQUESTED", c.paymentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("게이트가 꺼져 있으면 취소는 Redis를 부르지 않고, 켜져 있으면 실제 취소만 좌석 키를 지운다")
    void gateReflection() {
        SeatGateRedisStore offGate = mock(SeatGateRedisStore.class);
        BookingService gateOff = services.bookingService(reflector(offGate, false));
        Confirmed off = fixtures.confirmed(1);
        gateOff.cancel(off.userId(), off.bookingId());
        verifyNoInteractions(offGate);
        assertThat(db.seat(off.seatIds().get(0))).containsEntry("status", "AVAILABLE");

        SeatGateRedisStore onGate = mock(SeatGateRedisStore.class);
        BookingService gateOn = services.bookingService(reflector(onGate, true));
        Confirmed on = fixtures.confirmed(2);
        gateOn.cancel(on.userId(), on.bookingId());
        verify(onGate).releaseSeatsOnly(new GateHold(on.userId(), on.pid(), on.holdToken(), on.seatIds()));

        SeatGateRedisStore againGate = mock(SeatGateRedisStore.class);
        services.bookingService(reflector(againGate, true)).cancel(on.userId(), on.bookingId());
        verify(againGate, never()).releaseSeatsOnly(any());
    }

    private void startPerformance(long pid) {
        jdbcTemplate.update("UPDATE performance SET starts_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", pid);
    }

    private LocalDateTime cancelledAt(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT cancelled_at FROM booking WHERE id = ?", LocalDateTime.class,
                bookingId);
    }

    private SeatGateReflector reflector(SeatGateRedisStore store, boolean gateEnabled) {
        return services.gateReflector(store, TestProperties.from(properties).gateEnabled(gateEnabled).build());
    }
}
