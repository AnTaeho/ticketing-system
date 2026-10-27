package com.example.ticketing.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.BookingRedis;
import com.example.ticketing.booking.hold.HoldExpiryTransaction;
import com.example.ticketing.booking.hold.SeatHoldService;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.gate.BookingKeys;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.payment.StartResult.Started;
import com.example.ticketing.booking.payment.domain.BookingPayment;
import com.example.ticketing.booking.payment.dto.PaymentResponse;
import com.example.ticketing.booking.payment.pg.PgResult;
import com.example.ticketing.booking.payment.pg.PgStatus;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@BookingIntegrationTest
@Import(StubPgTestConfig.class)
class BookingPaymentServiceTest {

    private static final long TOLERANCE_MS = 5_000;

    @Autowired
    private BookingPaymentService paymentService;
    @Autowired
    private BookingPaymentTransaction paymentTransaction;
    @Autowired
    private BookingPaymentRepository paymentRepository;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private HoldExpiryTransaction expiryTransaction;
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
    @DisplayName("확정: booking 1·booking_seat N(가격 일치)·outbox TICKET_ISSUED 1, hold CONFIRMED, 좌석 SOLD, "
            + "결제 APPROVED·next_check_at NULL, 좌석 키 PTTL ≈ gate_close_at, userhold 키 없음, 예매번호 형식")
    void confirm() {
        Fixture f = newHold(2);

        PaymentResponse response = paymentService.pay(f.userId, f.holdId, key(), Optional.empty());

        assertThat(response.status()).isEqualTo(PaymentResponse.Status.CONFIRMED);
        long paymentId = response.paymentId();
        long bookingId = response.bookingId();
        LocalDateTime now = db.dbNow();
        String expectedNumber = "B" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", f.holdId);
        assertThat(response.bookingNumber()).matches("B\\d{8}-\\d{6,}").isEqualTo(expectedNumber);
        System.out.println("[S4.3] 확정 응답: " + response);

        Map<String, Object> booking = jdbcTemplate.queryForMap(
                "SELECT hold_id, payment_id, user_id, performance_id, total_amount, status, booking_number "
                        + "FROM booking WHERE id = ?", bookingId);
        assertThat(booking).containsEntry("hold_id", f.holdId).containsEntry("payment_id", paymentId)
                .containsEntry("user_id", f.userId).containsEntry("performance_id", f.pid)
                .containsEntry("total_amount", f.totalAmount).containsEntry("status", "CONFIRMED")
                .containsEntry("booking_number", expectedNumber);
        assertThat(db.bookingCountOfHold(f.holdId)).isEqualTo(1);

        List<Map<String, Object>> copied = jdbcTemplate.queryForList(
                "SELECT bs.seat_id, bs.price, s.price AS seat_price, bs.section, s.section AS seat_section, "
                        + "bs.row_no, s.row_no AS seat_row, bs.seat_no, s.seat_no AS seat_seat_no, "
                        + "bs.grade, s.grade AS seat_grade "
                        + "FROM booking_seat bs JOIN seat s ON s.id = bs.seat_id "
                        + "WHERE bs.booking_id = ? ORDER BY bs.seat_id", bookingId);
        assertThat(copied).extracting(row -> ((Number) row.get("seat_id")).longValue())
                .containsExactlyElementsOf(f.seatIds);
        assertThat(copied).allSatisfy(row -> {
            assertThat(row.get("price")).isEqualTo(row.get("seat_price"));
            assertThat(row.get("section")).isEqualTo(row.get("seat_section"));
            assertThat(row.get("row_no")).isEqualTo(row.get("seat_row"));
            assertThat(row.get("seat_no")).isEqualTo(row.get("seat_seat_no"));
            assertThat(row.get("grade")).isEqualTo(row.get("seat_grade"));
        });
        assertThat(copied.stream().mapToInt(row -> ((Number) row.get("price")).intValue()).sum())
                .isEqualTo(f.totalAmount);

        assertThat(db.outboxCount("TICKET_ISSUED", bookingId)).isEqualTo(1);
        Map<String, Object> outbox = db.outbox("TICKET_ISSUED", bookingId);
        assertThat(outbox).containsEntry("status", "PENDING").containsEntry("attempt", 0);
        assertThat(outbox.get("locked_until")).isNull();
        JsonNode payload = jsonMapper.readTree((String) outbox.get("payload"));
        assertThat(payload.get("bookingId").asLong()).isEqualTo(bookingId);
        assertThat(payload.get("userId").asLong()).isEqualTo(f.userId);
        assertThat(payload.get("performanceId").asLong()).isEqualTo(f.pid);

        assertThat(db.holdStatus(f.holdId)).isEqualTo("CONFIRMED");
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId))
                .containsEntry("status", "SOLD").containsEntry("hold_id", f.holdId).containsEntry("held_until", null));

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "APPROVED").containsEntry("next_check_at", null)
                .containsEntry("amount", f.totalAmount).containsEntry("hold_id", f.holdId);
        assertThat(payment.get("pg_tx_id")).isEqualTo("stub-tx-" + payment.get("order_id"));
        assertThat((String) payment.get("order_id")).hasSize(32).doesNotContain("-");

        assertThat(stubPg.approveCalls()).isEqualTo(1);
        assertThat(stubPg.approvals().get(0).amount()).isEqualTo(f.totalAmount);
        assertThat(stubPg.approvals().get(0).orderId()).isEqualTo(payment.get("order_id"));

        LocalDateTime gateCloseAt = jdbcTemplate.queryForObject(
                "SELECT gate_close_at FROM performance WHERE id = ?", LocalDateTime.class, f.pid);
        long expectedMs = Duration.between(now, gateCloseAt).toMillis();
        for (long seatId : f.seatIds) {
            String seatKey = BookingKeys.seat(f.pid, seatId);
            assertThat(redis.opsForValue().get(seatKey)).isEqualTo(f.holdToken);
            assertThat(pttl(seatKey)).isBetween(expectedMs - TOLERANCE_MS, expectedMs + TOLERANCE_MS);
        }
        assertThat(redis.hasKey(BookingKeys.userHold(f.pid, f.userId))).isFalse();
        System.out.printf("[S4.3] 확정 좌석 키 PTTL=%dms, gate_close_at까지=%dms%n",
                pttl(BookingKeys.seat(f.pid, f.seatIds.get(0))), expectedMs);
    }

    @Test
    @DisplayName("Existing APPROVED·CONFIRMED: 같은 키로 다시 보내면 PG 호출 없이 200 CONFIRMED, 같은 예매번호")
    void existingConfirmed() {
        Fixture f = newHold(1);
        String key = key();
        PaymentResponse first = paymentService.pay(f.userId, f.holdId, key, Optional.empty());

        PaymentResponse again = paymentService.pay(f.userId, f.holdId, key, Optional.empty());

        assertThat(again).isEqualTo(first);
        assertThat(again.status()).isEqualTo(PaymentResponse.Status.CONFIRMED);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
        assertThat(db.bookingCountOfHold(f.holdId)).isEqualTo(1);
    }

    @Test
    @DisplayName("거절: 결제 DECLINED, hold RELEASED, 좌석 AVAILABLE, 게이트 키 삭제, 402 + detail.paymentId. "
            + "같은 키 재요청도 402(PG 호출 없음)")
    void declined() {
        Fixture f = newHold(2);
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));
        String key = key();

        BookingException declined = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key, Optional.empty()));

        assertThat(declined.getCode()).isEqualTo(BookingErrorCode.PAYMENT_DECLINED);
        long paymentId = ((Number) declined.getDetail().get("paymentId")).longValue();
        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED").containsEntry("next_check_at", null)
                .containsEntry("hold_id", f.holdId);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("RELEASED");
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null).containsEntry("held_until", null));
        assertThat(redisKeyCleaner.gateKeys(f.pid)).isEmpty();
        assertThat(db.bookingCountOfHold(f.holdId)).isZero();

        BookingException again = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key, Optional.empty()));
        assertThat(again.getCode()).isEqualTo(BookingErrorCode.PAYMENT_DECLINED);
        assertThat(again.getDetail()).containsEntry("paymentId", paymentId);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("타임아웃(UNKNOWN): 결제 UNKNOWN·next_check_at ≈ NOW+10s·last_error, hold PAYING, 좌석 HELD, 202 UNKNOWN, "
            + "게이트 키는 결제 마감+1분으로 연장")
    void unknownKeepsSeats() {
        Fixture f = newHold(2);
        stubPg.approveWith(PgResult.unknown("read timeout 3s"));

        PaymentResponse response = paymentService.pay(f.userId, f.holdId, key(), Optional.empty());

        assertThat(response.status()).isEqualTo(PaymentResponse.Status.UNKNOWN);
        assertThat(response.bookingId()).isNull();
        Map<String, Object> payment = db.payment(response.paymentId());
        assertThat(payment).containsEntry("status", "UNKNOWN").containsEntry("last_error", "read timeout 3s")
                .containsEntry("pg_tx_id", null);
        long recheckMs = jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), next_check_at) FROM booking_payment WHERE id = ?",
                Long.class, response.paymentId()) / 1_000;
        assertThat(recheckMs).isBetween(10_000L - TOLERANCE_MS, 10_000L);

        assertThat(db.holdStatus(f.holdId)).isEqualTo("PAYING");
        LocalDateTime payDeadline = db.payDeadline(f.holdId);
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "HELD")
                .containsEntry("hold_id", f.holdId).containsEntry("held_until", payDeadline));
        long payDeadlineMs = Duration.between(db.dbNow(), payDeadline).toMillis();
        long expectedTtl = payDeadlineMs + Duration.ofMinutes(1).toMillis();
        assertThat(pttl(BookingKeys.userHold(f.pid, f.userId)))
                .isBetween(expectedTtl - TOLERANCE_MS, expectedTtl + TOLERANCE_MS);
        for (long seatId : f.seatIds) {
            assertThat(pttl(BookingKeys.seat(f.pid, seatId)))
                    .isBetween(expectedTtl - TOLERANCE_MS, expectedTtl + TOLERANCE_MS);
        }
    }

    @Test
    @DisplayName("Existing UNKNOWN·PAYING: 같은 키 재요청은 PG를 다시 부르지 않고 202 UNKNOWN")
    void existingUnknown() {
        Fixture f = newHold(1);
        stubPg.approveWith(PgResult.unknown("timeout"));
        String key = key();
        PaymentResponse first = paymentService.pay(f.userId, f.holdId, key, Optional.empty());

        PaymentResponse again = paymentService.pay(f.userId, f.holdId, key, Optional.empty());

        assertThat(again).isEqualTo(PaymentResponse.pending(first.paymentId(), PaymentResponse.Status.UNKNOWN));
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Existing REQUESTED·PAYING: 결제 시작만 커밋된 상태에서 같은 키 재요청은 PG 호출 없이 202 PROCESSING")
    void existingProcessing() {
        Fixture f = newHold(1);
        String key = key();
        Started started = (Started) paymentTransaction.start(f.userId, f.holdId, key);

        PaymentResponse again = paymentService.pay(f.userId, f.holdId, key, Optional.empty());

        assertThat(again).isEqualTo(PaymentResponse.pending(started.paymentId(), PaymentResponse.Status.PROCESSING));
        assertThat(stubPg.approveCalls()).isZero();
        assertThat(db.payment(started.paymentId())).containsEntry("status", "REQUESTED");
        long recheckMs = jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), next_check_at) FROM booking_payment WHERE id = ?",
                Long.class, started.paymentId()) / 1_000;
        assertThat(recheckMs).isBetween(30_000L - TOLERANCE_MS, 30_000L);
    }

    @Test
    @DisplayName("다른 키로 이미 결제가 있는 hold는 409 ALREADY_PAYING")
    void otherKeyIsAlreadyPaying() {
        Fixture f = newHold(1);
        paymentService.pay(f.userId, f.holdId, key(), Optional.empty());

        BookingException e = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key(), Optional.empty()));

        assertThat(e.getCode()).isEqualTo(BookingErrorCode.ALREADY_PAYING);
        assertThat(db.paymentCountOfHold(f.holdId)).isEqualTo(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 후 결제 시작: 409 HOLD_EXPIRED, hold HELD 그대로, 결제 행·PG 호출 없음")
    void expiredHold() {
        Fixture f = newHold(2);
        db.expireHold(f.holdId);

        BookingException e = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key(), Optional.empty()));

        assertThat(e.getCode()).isEqualTo(BookingErrorCode.HOLD_EXPIRED);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("HELD");
        assertThat(db.payDeadline(f.holdId)).isNull();
        assertThat(db.paymentCountOfHold(f.holdId)).isZero();
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("풀린 hold(RELEASED)는 409 HOLD_NOT_PAYABLE(detail.status), 남의 hold는 404 HOLD_NOT_FOUND")
    void notPayableAndNotFound() {
        Fixture f = newHold(1);
        seatHoldService.release(f.userId, f.holdId);

        BookingException released = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key(), Optional.empty()));
        assertThat(released.getCode()).isEqualTo(BookingErrorCode.HOLD_NOT_PAYABLE);
        assertThat(released.getDetail()).containsEntry("status", "RELEASED");

        Fixture other = newHold(1);
        long stranger = fixtures.newUser();
        BookingException notFound = catchBooking(() -> paymentService.pay(stranger, other.holdId, key(), Optional.empty()));
        assertThat(notFound.getCode()).isEqualTo(BookingErrorCode.HOLD_NOT_FOUND);
        assertThat(db.holdStatus(other.holdId)).isEqualTo("HELD");
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("끝난 결제(DECLINED·CANCELLED)가 있는 hold에 다른 키로 보내면 409 HOLD_NOT_PAYABLE(detail.status RELEASED), "
            + "결제 행은 그대로 1")
    void finishedPaymentOnHoldIsNotPayable() {
        Fixture declined = newHold(1);
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));
        catchBooking(() -> paymentService.pay(declined.userId, declined.holdId, key(), Optional.empty()));
        Fixture cancelled = newHold(1);
        stubPg.approveWith(PgResult.unknown("timeout"));
        long cancelledPayment = paymentService.pay(cancelled.userId, cancelled.holdId, key(), Optional.empty())
                .paymentId();
        paymentTransaction.cancelAndRelease(cancelledPayment);

        for (Fixture f : List.of(declined, cancelled)) {
            BookingException e = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key(), Optional.empty()));
            assertThat(e.getCode()).isEqualTo(BookingErrorCode.HOLD_NOT_PAYABLE);
            assertThat(e.getDetail()).containsEntry("status", "RELEASED").containsEntry("holdId", f.holdId);
            assertThat(db.paymentCountOfHold(f.holdId)).isEqualTo(1);
        }
        assertThat(stubPg.approveCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("만료 스케줄러가 EXPIRED로 바꾼 hold는 409 HOLD_EXPIRED, 결제 행·PG 호출 없음")
    void expiredStatusHoldIsHoldExpired() {
        Fixture f = newHold(1);
        db.expireHold(f.holdId);
        for (int round = 0; round < 50 && !"EXPIRED".equals(db.holdStatus(f.holdId)); round++) {
            expiryTransaction.claimAndExpire(50);
        }
        assertThat(db.holdStatus(f.holdId)).isEqualTo("EXPIRED");

        BookingException e = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key(), Optional.empty()));

        assertThat(e.getCode()).isEqualTo(BookingErrorCode.HOLD_EXPIRED);
        assertThat(db.paymentCountOfHold(f.holdId)).isZero();
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("빈 멱등키는 400 IDEMPOTENCY_KEY_REQUIRED, 64자 초과는 400 INVALID_REQUEST")
    void idempotencyKeyValidation() {
        Fixture f = newHold(1);

        assertThat(catchBooking(() -> paymentService.pay(f.userId, f.holdId, "  ", Optional.empty())).getCode())
                .isEqualTo(BookingErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        assertThat(catchBooking(() -> paymentService.pay(f.userId, f.holdId, "k".repeat(65), Optional.empty()))
                .getCode()).isEqualTo(BookingErrorCode.INVALID_REQUEST);
        assertThat(db.paymentCountOfHold(f.holdId)).isZero();
    }

    @Test
    @DisplayName("결제 중 좌석 보호: expires_at이 지나도 좌석 held_until = pay_deadline, 다른 사용자 선점은 게이트·DB 모두 409, "
            + "만료 스케줄러도 건드리지 않음")
    void payingSeatsAreProtected() {
        Fixture f = newHold(2);
        stubPg.approveWith(PgResult.unknown("timeout"));
        paymentService.pay(f.userId, f.holdId, key(), Optional.empty());
        db.expireHold(f.holdId);

        LocalDateTime payDeadline = db.payDeadline(f.holdId);
        assertThat(payDeadline).isNotNull();
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("held_until", payDeadline));

        long other = fixtures.newUser();
        String queueToken = fixtures.admit(f.pid, other);
        BookingException byGate = catchBooking(() -> seatHoldService.hold(other, f.pid, f.seatIds, queueToken));
        assertThat(byGate.getCode()).isEqualTo(BookingErrorCode.SEAT_TAKEN);

        redisKeyCleaner.clean(f.pid);
        String retryToken = fixtures.admit(f.pid, other);
        BookingException byDb = catchBooking(() -> seatHoldService.hold(other, f.pid, f.seatIds, retryToken));
        assertThat(byDb.getCode()).isEqualTo(BookingErrorCode.SEAT_STATE_CHANGED);

        assertThat(expiryTransaction.claimAndExpire(50))
                .noneMatch(expired -> expired.holdId() == f.holdId);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("PAYING");
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "HELD")
                .containsEntry("hold_id", f.holdId).containsEntry("held_until", payDeadline));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE user_id = ?", Long.class, other))
                .isZero();
    }

    @Test
    @DisplayName("좌석 상실: 승인 직전 좌석 하나를 남의 hold로 바꿔치기 → hold REFUNDING, 남은 좌석 AVAILABLE, "
            + "outbox REFUND_REQUESTED 1, 409 SEAT_LOST_REFUNDING. 같은 키 재요청도 409")
    void seatLostWhileApproving() {
        Fixture f = newHold(2);
        long kept = f.seatIds.get(0);
        long lost = f.seatIds.get(1);
        long thief = fixtures.newUser();
        HoldCreated thiefHold = seatHoldService.hold(thief, f.pid, List.of(db.seatIds(f.pid).get(2)),
                fixtures.admit(f.pid, thief));
        stubPg.beforeApproveReturns(orderId ->
                jdbcTemplate.update("UPDATE seat SET hold_id = ? WHERE id = ?", thiefHold.holdId(), lost));
        String key = key();

        BookingException e = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key, Optional.empty()));

        assertThat(e.getCode()).isEqualTo(BookingErrorCode.SEAT_LOST_REFUNDING);
        long paymentId = ((Number) e.getDetail().get("paymentId")).longValue();
        assertThat(db.holdStatus(f.holdId)).isEqualTo("REFUNDING");
        assertThat(db.seat(kept)).containsEntry("status", "AVAILABLE").containsEntry("hold_id", null)
                .containsEntry("held_until", null);
        assertThat(db.seat(lost)).containsEntry("status", "HELD").containsEntry("hold_id", thiefHold.holdId());
        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "APPROVED").containsEntry("next_check_at", null);
        assertThat(db.bookingCountOfHold(f.holdId)).isZero();
        assertThat(db.outboxCount("REFUND_REQUESTED", paymentId)).isEqualTo(1);
        JsonNode refund = jsonMapper.readTree((String) db.outbox("REFUND_REQUESTED", paymentId).get("payload"));
        assertThat(refund.get("paymentId").asLong()).isEqualTo(paymentId);
        assertThat(refund.get("orderId").asString()).isEqualTo(payment.get("order_id"));

        assertThat(redis.hasKey(BookingKeys.userHold(f.pid, f.userId))).isFalse();
        assertThat(redis.hasKey(BookingKeys.seat(f.pid, kept))).isFalse();
        assertThat(redis.hasKey(BookingKeys.seat(f.pid, lost))).isFalse();
        assertThat(redis.opsForValue().get(BookingKeys.seat(f.pid, db.seatIds(f.pid).get(2))))
                .isEqualTo(db.holdToken(thiefHold.holdId()));

        BookingException again = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key, Optional.empty()));
        assertThat(again.getCode()).isEqualTo(BookingErrorCode.SEAT_LOST_REFUNDING);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelAndRelease: 결과 모름 결제를 CANCELLED, hold RELEASED, 좌석 AVAILABLE. 두 번째는 변화 없음. "
            + "같은 키 재요청은 409 PAYMENT_CANCELLED")
    void cancelAndRelease() {
        Fixture f = newHold(2);
        stubPg.approveWith(PgResult.unknown("timeout"));
        String key = key();
        long paymentId = paymentService.pay(f.userId, f.holdId, key, Optional.empty()).paymentId();

        PaymentSettlement first = paymentTransaction.cancelAndRelease(paymentId);
        PaymentSettlement second = paymentTransaction.cancelAndRelease(paymentId);

        assertThat(first.applied()).isTrue();
        assertThat(first.outcome()).isEqualTo(PaymentOutcome.CANCELLED);
        assertThat(first.seatIds()).containsExactlyElementsOf(f.seatIds);
        assertThat(first.holdToken()).isEqualTo(f.holdToken);
        assertThat(second.applied()).isFalse();
        assertThat(second.outcome()).isEqualTo(PaymentOutcome.CANCELLED);
        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(f.holdId)).isEqualTo("RELEASED");
        f.seatIds.forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));

        BookingException again = catchBooking(() -> paymentService.pay(f.userId, f.holdId, key, Optional.empty()));
        assertThat(again.getCode()).isEqualTo(BookingErrorCode.PAYMENT_CANCELLED);
        assertThat(again.getDetail()).containsEntry("paymentId", paymentId);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 끝난 결제에 confirm·decline·markUnknown을 다시 불러도 아무것도 바꾸지 않고 현재 결과를 돌려준다")
    void settledPaymentIsNotChangedAgain() {
        Fixture f = newHold(1);
        PaymentResponse confirmed = paymentService.pay(f.userId, f.holdId, key(), Optional.empty());
        long paymentId = confirmed.paymentId();

        PaymentSettlement decline = paymentTransaction.decline(paymentId);
        PaymentSettlement confirmAgain = paymentTransaction.confirm(paymentId, "other-tx");
        PaymentSettlement unknown = paymentTransaction.markUnknown(paymentId, "late timeout");

        assertThat(List.of(decline, confirmAgain, unknown)).allSatisfy(s -> {
            assertThat(s.applied()).isFalse();
            assertThat(s.outcome()).isEqualTo(PaymentOutcome.CONFIRMED);
            assertThat(s.bookingId()).isEqualTo(confirmed.bookingId());
            assertThat(s.bookingNumber()).isEqualTo(confirmed.bookingNumber());
        });
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED").containsEntry("last_error", null)
                .containsEntry("pg_tx_id", "stub-tx-" + db.payment(paymentId).get("order_id"));
        assertThat(db.holdStatus(f.holdId)).isEqualTo("CONFIRMED");
        assertThat(db.bookingCountOfHold(f.holdId)).isEqualTo(1);
    }

    @Test
    @DisplayName("유니크 위반은 제약 이름으로 구분한다: uk_payment_hold / uk_payment_idem")
    void violatedConstraintNames() {
        long holdId = -Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) - 1;
        long userId = fixtures.newUser();
        String key = key();
        List<Long> created = new ArrayList<>();
        try {
            created.add(paymentRepository.save(BookingPayment.requested(holdId, userId, key, orderId(), 1000,
                    null)).getId());

            DataIntegrityViolationException sameHold = catchDuplicate(() -> paymentRepository.save(
                    BookingPayment.requested(holdId, userId, key(), orderId(), 1000, null)));
            DataIntegrityViolationException sameKey = catchDuplicate(() -> paymentRepository.save(
                    BookingPayment.requested(holdId - 1, userId, key, orderId(), 1000, null)));

            String holdViolation = BookingPaymentService.violatedConstraint(sameHold);
            String keyViolation = BookingPaymentService.violatedConstraint(sameKey);
            System.out.println("[S4.3] 유니크 위반 원인: hold=" + holdViolation + " / idem=" + keyViolation);
            assertThat(holdViolation).contains(BookingPayment.UK_HOLD).doesNotContain(BookingPayment.UK_IDEMPOTENCY);
            assertThat(keyViolation).contains(BookingPayment.UK_IDEMPOTENCY).doesNotContain(BookingPayment.UK_HOLD);
        } finally {
            jdbcTemplate.update("DELETE FROM booking_payment WHERE user_id = ?", userId);
        }
    }

    @Test
    @DisplayName("게이트 OFF면 결제의 연장·확정·해제 어느 경로도 Redis 게이트를 부르지 않는다")
    void gateOffSkipsRedis() {
        RedisSeatGate gate = mock(RedisSeatGate.class);
        BookingProperties gateOff = new BookingProperties(properties.seed(), properties.redis(), properties.hold(),
                properties.payment(), new BookingProperties.Gate(false, properties.gate().fallbackMaxConcurrency()),
                properties.queue(), properties.seatmap(), properties.outbox(), properties.ops(), properties.pg(),
                properties.fakePg());
        BookingPaymentService service = new BookingPaymentService(paymentTransaction, stubPg, gate, gateOff);
        Fixture confirmed = newHold(1);
        Fixture declined = newHold(1);
        Fixture unknown = newHold(1);

        assertThat(service.pay(confirmed.userId, confirmed.holdId, key(), Optional.empty()).status())
                .isEqualTo(PaymentResponse.Status.CONFIRMED);
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));
        assertThat(catchBooking(() -> service.pay(declined.userId, declined.holdId, key(), Optional.empty())).getCode())
                .isEqualTo(BookingErrorCode.PAYMENT_DECLINED);
        stubPg.approveWith(PgResult.unknown("timeout"));
        assertThat(service.pay(unknown.userId, unknown.holdId, key(), Optional.empty()).status())
                .isEqualTo(PaymentResponse.Status.UNKNOWN);

        verifyNoInteractions(gate);
        assertThat(db.holdStatus(confirmed.holdId)).isEqualTo("CONFIRMED");
        assertThat(db.holdStatus(declined.holdId)).isEqualTo("RELEASED");
        assertThat(db.holdStatus(unknown.holdId)).isEqualTo("PAYING");
        // 게이트 ON으로 잡은 선점 키는 결제가 건드리지 않았으므로 처음 TTL(선점 5분) 안에 그대로 있다
        assertThat(pttl(BookingKeys.seat(confirmed.pid, confirmed.seatIds.get(0))))
                .isBetween(1L, properties.hold().ttl().toMillis());
    }

    private Fixture newHold(int seatCount) {
        long pid = fixtures.newPerformance(1, 1, 6);
        performances.add(pid);
        long userId = fixtures.newUser();
        List<Long> seatIds = db.seatIds(pid).subList(0, seatCount);
        HoldCreated hold = seatHoldService.hold(userId, pid, seatIds, fixtures.admit(pid, userId));
        return new Fixture(pid, userId, hold.holdId(), List.copyOf(seatIds), hold.totalAmount(),
                db.holdToken(hold.holdId()));
    }

    private long pttl(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        return ttl == null ? -3 : ttl;
    }

    private static BookingException catchBooking(Runnable action) {
        try {
            action.run();
        } catch (BookingException e) {
            return e;
        }
        throw new AssertionError("BookingException이 나야 한다");
    }

    private static DataIntegrityViolationException catchDuplicate(Runnable action) {
        try {
            action.run();
        } catch (DataIntegrityViolationException e) {
            return e;
        }
        throw new AssertionError("DataIntegrityViolationException이 나야 한다");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String orderId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Fixture(long pid, long userId, long holdId, List<Long> seatIds, int totalAmount,
                           String holdToken) {
    }
}
