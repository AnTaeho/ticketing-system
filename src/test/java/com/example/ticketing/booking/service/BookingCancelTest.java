package com.example.ticketing.booking.service;

import static com.example.ticketing.support.ApiErrors.catchApiException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.dto.BookingResult;
import com.example.ticketing.booking.dto.CancelResult;
import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
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

@IntegrationTest
@Import(StubPgTestConfig.class)
class BookingCancelTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingReader bookingReader;
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
    @DisplayName("확정 예매를 취소하면 PG 환불 뒤 좌석이 풀리고 결제가 CANCELLED가 되어 다른 사용자가 바로 잡을 수 있다")
    void cancelConfirmedBooking() {
        Confirmed c = fixtures.confirmed(2);
        c.seatIds().forEach(seatId -> assertThat(redis.opsForValue().get(RedisKeys.seat(c.pid(), seatId)))
                .isEqualTo(c.holdToken()));

        CancelResult response = bookingService.cancel(c.userId(), c.bookingId());

        assertThat(response).isEqualTo(new CancelResult(c.bookingId(), BookingStatus.CANCELLED));
        Map<String, Object> booking = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at FROM booking WHERE booking_id = ?", c.bookingId());
        assertThat(booking).containsEntry("status", "CANCELLED");
        assertThat(booking.get("cancelled_at")).isNotNull();
        c.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        c.seatIds().forEach(seatId -> assertThat(redis.hasKey(RedisKeys.seat(c.pid(), seatId))).isFalse());
        assertThat(db.payment(c.paymentId())).containsEntry("status", "CANCELLED");
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
        assertThat(stubPg.cancelCalls()).isEqualTo(1);
        assertThat(cancelledAt(c.bookingId())).isEqualTo(cancelledAt);
        assertThat(db.seat(c.seatIds().get(0))).containsEntry("status", "HELD")
                .containsEntry("hold_id", retaken.holdId());
        assertThat(redis.opsForValue().get(RedisKeys.seat(c.pid(), c.seatIds().get(0))))
                .isEqualTo(retaken.holdToken());
    }

    @Test
    @DisplayName("2스레드가 동시에 취소하면 둘 다 같은 응답을 받고 결제는 CANCELLED다")
    void cancelConcurrently() {
        Confirmed c = fixtures.confirmed(2);

        Results<CancelResult> results = Concurrently.of(2)
                .readyWithin(Duration.ofSeconds(10))
                .within(Duration.ofSeconds(30))
                .run(i -> bookingService.cancel(c.userId(), c.bookingId()));

        assertThat(results.errors()).isEmpty();
        assertThat(results.values()).hasSize(2).containsOnly(new CancelResult(c.bookingId(), BookingStatus.CANCELLED));
        assertThat(db.payment(c.paymentId())).containsEntry("status", "CANCELLED");
        c.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));
        assertThat(testRedis.keys(c.pid())).isEmpty();
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
        assertThat(jdbcTemplate.queryForMap("SELECT status, cancelled_at FROM booking WHERE booking_id = ?", c.bookingId()))
                .containsEntry("status", "CONFIRMED").containsEntry("cancelled_at", null);
        assertThat(db.seat(c.seatIds().get(0))).containsEntry("status", "SOLD").containsEntry("hold_id", c.holdId());
        assertThat(stubPg.cancelCalls()).isZero();
        assertThat(redis.opsForValue().get(RedisKeys.seat(c.pid(), c.seatIds().get(0)))).isEqualTo(c.holdToken());
        assertThat(bookingReader.find(c.userId(), c.bookingId()).cancellable()).isFalse();
    }

    @Test
    @DisplayName("이미 취소한 예매는 회차가 시작된 뒤 다시 보내도 현재 상태를 돌려준다")
    void cancelledBookingStaysIdempotentAfterStart() {
        Confirmed c = fixtures.confirmed(1);
        CancelResult first = bookingService.cancel(c.userId(), c.bookingId());
        startPerformance(c.pid());

        assertThat(bookingService.cancel(c.userId(), c.bookingId())).isEqualTo(first);
        assertThat(stubPg.cancelCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 예매나 없는 예매를 취소·조회하면 404로 답하고 아무것도 바꾸지 않는다")
    void othersBookingIsNotFound() {
        Confirmed c = fixtures.confirmed(1);
        long stranger = fixtures.newUser();

        assertThat(catchApiException(() -> bookingService.cancel(stranger, c.bookingId())).getCode())
                .isEqualTo(ErrorCode.BOOKING_NOT_FOUND);
        assertThat(catchApiException(() -> bookingReader.find(stranger, c.bookingId())).getCode())
                .isEqualTo(ErrorCode.BOOKING_NOT_FOUND);
        assertThat(catchApiException(() -> bookingService.cancel(c.userId(), Long.MAX_VALUE)).getCode())
                .isEqualTo(ErrorCode.BOOKING_NOT_FOUND);
        assertThat(bookingReader.findByUser(stranger)).isEmpty();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM booking WHERE booking_id = ?", String.class, c.bookingId()))
                .isEqualTo("CONFIRMED");
        assertThat(db.seat(c.seatIds().get(0))).containsEntry("status", "SOLD");
        assertThat(stubPg.cancelCalls()).isZero();
    }

    @Test
    @DisplayName("PG 환불이 결과 모름이면 503 REFUND_FAILED이고 예매·좌석·결제가 그대로이며, PG가 정상으로 돌아와 다시 보내면 취소된다")
    void refundUnknownKeepsEverythingThenRetrySucceeds() {
        Confirmed c = fixtures.confirmed(2);
        stubPg.cancelWith(PgResult.unknown("read timeout 3s"));

        ApiException e = catchApiException(() -> bookingService.cancel(c.userId(), c.bookingId()));

        assertThat(e.getCode()).isEqualTo(ErrorCode.REFUND_FAILED);
        assertThat(jdbcTemplate.queryForMap("SELECT status, cancelled_at FROM booking WHERE booking_id = ?", c.bookingId()))
                .containsEntry("status", "CONFIRMED").containsEntry("cancelled_at", null);
        c.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "SOLD")
                .containsEntry("hold_id", c.holdId()));
        assertThat(db.payment(c.paymentId())).containsEntry("status", "APPROVED");
        c.seatIds().forEach(seatId -> assertThat(redis.opsForValue().get(RedisKeys.seat(c.pid(), seatId)))
                .isEqualTo(c.holdToken()));

        stubPg.cancelWith(PgResult.of(PgStatus.CANCELLED, null));
        CancelResult retried = bookingService.cancel(c.userId(), c.bookingId());

        assertThat(retried).isEqualTo(new CancelResult(c.bookingId(), BookingStatus.CANCELLED));
        assertThat(db.payment(c.paymentId())).containsEntry("status", "CANCELLED");
        BookingResult detail = bookingReader.find(c.userId(), c.bookingId());
        assertThat(detail.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(detail.cancellable()).isFalse();
        assertThat(detail.seats()).hasSize(2);
        c.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE"));
    }

    @Test
    @DisplayName("실제로 취소한 호출만 좌석 키를 지우고 이미 취소된 예매를 다시 취소하면 지우지 않는다")
    void gateReflection() {
        SeatGateRedisStore gate = mock(SeatGateRedisStore.class);
        Confirmed c = fixtures.confirmed(2);
        bookingServiceWith(gate).cancel(c.userId(), c.bookingId());
        verify(gate).release(new GateHold(c.pid(), c.holdToken(), c.seatIds()));

        SeatGateRedisStore againGate = mock(SeatGateRedisStore.class);
        bookingServiceWith(againGate).cancel(c.userId(), c.bookingId());
        verify(againGate, never()).release(any());
    }

    private void startPerformance(long pid) {
        jdbcTemplate.update("UPDATE performance SET starts_at = NOW(6) - INTERVAL 1 SECOND WHERE performance_id = ?", pid);
    }

    private LocalDateTime cancelledAt(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT cancelled_at FROM booking WHERE booking_id = ?", LocalDateTime.class,
                bookingId);
    }

    private BookingService bookingServiceWith(SeatGateRedisStore store) {
        return services.bookingService(services.gateReflector(store, properties));
    }
}
