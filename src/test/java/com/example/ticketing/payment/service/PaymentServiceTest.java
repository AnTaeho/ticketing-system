package com.example.ticketing.payment.service;

import static com.example.ticketing.support.ApiErrors.catchApiException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.jdbc.ConstraintViolations;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.service.HoldExpiryTransaction;
import com.example.ticketing.hold.service.SeatHoldService;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResponse;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.payment.repository.PaymentRepository;
import com.example.ticketing.support.Fixtures;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

@IntegrationTest
@Import(StubPgTestConfig.class)
class PaymentServiceTest {

    private static final long TOLERANCE_MS = 5_000;

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentTransaction paymentTransaction;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private HoldExpiryTransaction holdExpiryTransaction;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestDb db;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private TestServices services;
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
    @DisplayName("결제가 승인되면 예매가 확정되고 좌석 키는 게이트 마감까지 남는다")
    void confirm() {
        Held held = fixtures.held(2);

        PaymentResult response = paymentService.pay(command(held, key()));

        assertThat(response.outcome()).isEqualTo(PaymentOutcome.CONFIRMED);
        long paymentId = response.paymentId();
        long bookingId = response.bookingId();
        LocalDateTime now = db.dbNow();
        String expectedNumber = "B" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", held.holdId());
        assertThat(response.bookingNumber()).matches("B\\d{8}-\\d{6,}").isEqualTo(expectedNumber);

        Map<String, Object> booking = jdbcTemplate.queryForMap(
                "SELECT hold_id, payment_id, user_id, performance_id, total_amount, status, booking_number "
                        + "FROM booking WHERE id = ?", bookingId);
        assertThat(booking).containsEntry("hold_id", held.holdId()).containsEntry("payment_id", paymentId)
                .containsEntry("user_id", held.userId()).containsEntry("performance_id", held.pid())
                .containsEntry("total_amount", held.totalAmount()).containsEntry("status", "CONFIRMED")
                .containsEntry("booking_number", expectedNumber);
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);

        List<Map<String, Object>> copied = jdbcTemplate.queryForList(
                "SELECT bs.seat_id, bs.price, s.price AS seat_price, bs.section, s.section AS seat_section, "
                        + "bs.row_no, s.row_no AS seat_row, bs.seat_no, s.seat_no AS seat_seat_no, "
                        + "bs.grade, s.grade AS seat_grade "
                        + "FROM booking_seat bs JOIN seat s ON s.id = bs.seat_id "
                        + "WHERE bs.booking_id = ? ORDER BY bs.seat_id", bookingId);
        assertThat(copied).extracting(row -> ((Number) row.get("seat_id")).longValue())
                .containsExactlyElementsOf(held.seatIds());
        assertThat(copied).allSatisfy(row -> {
            assertThat(row.get("price")).isEqualTo(row.get("seat_price"));
            assertThat(row.get("section")).isEqualTo(row.get("seat_section"));
            assertThat(row.get("row_no")).isEqualTo(row.get("seat_row"));
            assertThat(row.get("seat_no")).isEqualTo(row.get("seat_seat_no"));
            assertThat(row.get("grade")).isEqualTo(row.get("seat_grade"));
        });
        assertThat(copied.stream().mapToInt(row -> ((Number) row.get("price")).intValue()).sum())
                .isEqualTo(held.totalAmount());

        assertThat(db.outboxCount("TICKET_ISSUED", bookingId)).isEqualTo(1);
        Map<String, Object> outbox = db.outboxTask("TICKET_ISSUED", bookingId);
        assertThat(outbox).containsEntry("status", "PENDING").containsEntry("attempt", 0);
        assertThat(outbox.get("locked_until")).isNull();
        JsonNode payload = jsonMapper.readTree((String) outbox.get("payload"));
        assertThat(payload.get("bookingId").asLong()).isEqualTo(bookingId);
        assertThat(payload.size()).isEqualTo(1);

        assertThat(db.holdStatus(held.holdId())).isEqualTo("CONFIRMED");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "SOLD")
                .containsEntry("hold_id", held.holdId()).containsEntry("held_until", null));

        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "APPROVED").containsEntry("next_check_at", null)
                .containsEntry("amount", held.totalAmount()).containsEntry("hold_id", held.holdId());
        assertThat(payment.get("pg_tx_id")).isEqualTo("stub-tx-" + payment.get("order_id"));
        assertThat((String) payment.get("order_id")).hasSize(32).doesNotContain("-");

        assertThat(stubPg.approveCalls()).isEqualTo(1);
        assertThat(stubPg.approvals().get(0).amount()).isEqualTo(held.totalAmount());
        assertThat(stubPg.approvals().get(0).orderId()).isEqualTo(payment.get("order_id"));

        LocalDateTime gateCloseAt = jdbcTemplate.queryForObject(
                "SELECT gate_close_at FROM performance WHERE id = ?", LocalDateTime.class, held.pid());
        long expectedMs = Duration.between(now, gateCloseAt).toMillis();
        for (long seatId : held.seatIds()) {
            String seatKey = RedisKeys.seat(held.pid(), seatId);
            assertThat(redis.opsForValue().get(seatKey)).isEqualTo(held.holdToken());
            assertThat(testRedis.pttl(seatKey)).isBetween(expectedMs - TOLERANCE_MS, expectedMs + TOLERANCE_MS);
        }
        assertThat(redis.hasKey(RedisKeys.userHold(held.userId(), held.pid()))).isFalse();
    }

    @Test
    @DisplayName("확정된 결제를 같은 키로 다시 보내면 PG를 부르지 않고 같은 결과를 준다")
    void existingConfirmed() {
        Held held = fixtures.held(1);
        String key = key();
        PaymentResult first = paymentService.pay(command(held, key));

        PaymentResult again = paymentService.pay(command(held, key));

        assertThat(again).isEqualTo(first);
        assertThat(again.outcome()).isEqualTo(PaymentOutcome.CONFIRMED);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
    }

    @Test
    @DisplayName("결제가 거절되면 선점이 풀리고 같은 키로 다시 보내도 402로 답한다")
    void declined() {
        Held held = fixtures.held(2);
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));
        String key = key();

        ApiException declined = catchApiException(() -> paymentService.pay(command(held, key)));

        assertThat(declined.getCode()).isEqualTo(ErrorCode.PAYMENT_DECLINED);
        long paymentId = ((Number) declined.getDetail().get("paymentId")).longValue();
        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED").containsEntry("next_check_at", null)
                .containsEntry("hold_id", held.holdId());
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null).containsEntry("held_until", null));
        assertThat(testRedis.gateKeys(held.pid())).isEmpty();
        assertThat(db.bookingCountOfHold(held.holdId())).isZero();

        ApiException again = catchApiException(() -> paymentService.pay(command(held, key)));
        assertThat(again.getCode()).isEqualTo(ErrorCode.PAYMENT_DECLINED);
        assertThat(again.getDetail()).containsEntry("paymentId", paymentId);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG 응답이 시간 초과면 결과 모름으로 두고 좌석과 게이트 키를 결제 마감까지 지킨다")
    void unknownKeepsSeats() {
        Held held = fixtures.held(2);
        stubPg.approveWith(PgResult.unknown("read timeout 3s"));

        PaymentResult response = paymentService.pay(command(held, key()));

        assertThat(response.outcome()).isEqualTo(PaymentOutcome.UNKNOWN);
        assertThat(response.bookingId()).isNull();
        Map<String, Object> payment = db.payment(response.paymentId());
        assertThat(payment).containsEntry("status", "UNKNOWN").containsEntry("last_error", "read timeout 3s")
                .containsEntry("pg_tx_id", null);
        assertThat(db.paymentRecheckMs(response.paymentId())).isBetween(10_000L - TOLERANCE_MS, 10_000L);

        assertThat(db.holdStatus(held.holdId())).isEqualTo("PAYING");
        LocalDateTime payDeadline = db.payDeadline(held.holdId());
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "HELD")
                .containsEntry("hold_id", held.holdId()).containsEntry("held_until", payDeadline));
        long payDeadlineMs = Duration.between(db.dbNow(), payDeadline).toMillis();
        long expectedTtl = payDeadlineMs + properties.payment().gateKeyMargin().toMillis();
        assertThat(testRedis.pttl(RedisKeys.userHold(held.userId(), held.pid())))
                .isBetween(expectedTtl - TOLERANCE_MS, expectedTtl + TOLERANCE_MS);
        for (long seatId : held.seatIds()) {
            assertThat(testRedis.pttl(RedisKeys.seat(held.pid(), seatId)))
                    .isBetween(expectedTtl - TOLERANCE_MS, expectedTtl + TOLERANCE_MS);
        }
    }

    @Test
    @DisplayName("결과 모름 결제를 같은 키로 다시 보내면 PG를 부르지 않고 202 UNKNOWN이다")
    void existingUnknown() {
        Held held = fixtures.held(1);
        stubPg.approveWith(PgResult.unknown("timeout"));
        String key = key();
        PaymentResult first = paymentService.pay(command(held, key));

        PaymentResult again = paymentService.pay(command(held, key));

        assertThat(PaymentResponse.from(again))
                .isEqualTo(new PaymentResponse(first.paymentId(), PaymentOutcome.UNKNOWN, null, null));
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 시작만 커밋된 결제를 같은 키로 보내면 PG 호출 없이 202 PROCESSING이다")
    void existingProcessing() {
        Held held = fixtures.held(1);
        String key = key();
        Started started = (Started) paymentTransaction.start(held.userId(), held.holdId(), key);

        PaymentResult again = paymentService.pay(command(held, key));

        assertThat(PaymentResponse.from(again))
                .isEqualTo(new PaymentResponse(started.paymentId(), PaymentOutcome.PROCESSING, null, null));
        assertThat(stubPg.approveCalls()).isZero();
        assertThat(db.payment(started.paymentId())).containsEntry("status", "REQUESTED");
        assertThat(db.paymentRecheckMs(started.paymentId())).isBetween(30_000L - TOLERANCE_MS, 30_000L);
    }

    @Test
    @DisplayName("다른 키로 이미 결제 중인 선점에 보내면 409 ALREADY_PAYING이다")
    void otherKeyIsAlreadyPaying() {
        Held held = fixtures.held(1);
        paymentService.pay(command(held, key()));

        ApiException e = catchApiException(() -> paymentService.pay(command(held, key())));

        assertThat(e.getCode()).isEqualTo(ErrorCode.ALREADY_PAYING);
        assertThat(db.paymentCountOfHold(held.holdId())).isEqualTo(1);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료된 선점에 결제를 시작하면 결제 행 없이 409 HOLD_EXPIRED다")
    void expiredHold() {
        Held held = fixtures.held(2);
        db.expireHold(held.holdId());

        ApiException e = catchApiException(() -> paymentService.pay(command(held, key())));

        assertThat(e.getCode()).isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("HELD");
        assertThat(db.payDeadline(held.holdId())).isNull();
        assertThat(db.paymentCountOfHold(held.holdId())).isZero();
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("풀린 선점은 409 HOLD_NOT_PAYABLE, 남의 선점은 404 HOLD_NOT_FOUND다")
    void notPayableAndNotFound() {
        Held held = fixtures.held(1);
        seatHoldService.release(held.userId(), held.holdId());

        ApiException released = catchApiException(() -> paymentService.pay(command(held, key())));
        assertThat(released.getCode()).isEqualTo(ErrorCode.HOLD_NOT_PAYABLE);
        assertThat(released.getDetail()).containsEntry("status", "RELEASED");

        Held other = fixtures.held(1);
        long stranger = fixtures.newUser();
        ApiException notFound = catchApiException(() -> paymentService.pay(
                new PayCommand(stranger, other.holdId(), key(), Optional.empty())));
        assertThat(notFound.getCode()).isEqualTo(ErrorCode.HOLD_NOT_FOUND);
        assertThat(db.holdStatus(other.holdId())).isEqualTo("HELD");
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("끝난 결제가 있는 선점에 다른 키로 보내면 409 HOLD_NOT_PAYABLE이다")
    void finishedPaymentOnHoldIsNotPayable() {
        Held declined = fixtures.held(1);
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));
        catchApiException(() -> paymentService.pay(command(declined, key())));
        Held cancelled = fixtures.held(1);
        stubPg.approveWith(PgResult.unknown("timeout"));
        long cancelledPayment = paymentService.pay(command(cancelled, key()))
                .paymentId();
        paymentTransaction.cancelAndRelease(cancelledPayment);

        for (Held held : List.of(declined, cancelled)) {
            ApiException e = catchApiException(() -> paymentService.pay(command(held, key())));
            assertThat(e.getCode()).isEqualTo(ErrorCode.HOLD_NOT_PAYABLE);
            assertThat(e.getDetail()).containsEntry("status", "RELEASED").containsEntry("holdId", held.holdId());
            assertThat(db.paymentCountOfHold(held.holdId())).isEqualTo(1);
        }
        assertThat(stubPg.approveCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("만료 스케줄러가 EXPIRED로 바꾼 선점에 결제하면 409 HOLD_EXPIRED다")
    void expiredStatusHoldIsHoldExpired() {
        Held held = fixtures.held(1);
        db.expireHold(held.holdId());
        for (int round = 0; round < 50 && !"EXPIRED".equals(db.holdStatus(held.holdId())); round++) {
            holdExpiryTransaction.claimAndExpire(50);
        }
        assertThat(db.holdStatus(held.holdId())).isEqualTo("EXPIRED");

        ApiException e = catchApiException(() -> paymentService.pay(command(held, key())));

        assertThat(e.getCode()).isEqualTo(ErrorCode.HOLD_EXPIRED);
        assertThat(db.paymentCountOfHold(held.holdId())).isZero();
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("결제 중인 선점은 만료 시각이 지나도 좌석을 남에게 내주지 않는다")
    void payingSeatsAreProtected() {
        Held held = fixtures.held(2);
        stubPg.approveWith(PgResult.unknown("timeout"));
        paymentService.pay(command(held, key()));
        db.expireHold(held.holdId());

        LocalDateTime payDeadline = db.payDeadline(held.holdId());
        assertThat(payDeadline).isNotNull();
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("held_until", payDeadline));

        long other = fixtures.newUser();
        String queueToken = fixtures.admit(held.pid(), other);
        ApiException byGate = catchApiException(() -> seatHoldService.hold(
                new CreateHoldCommand(other, held.pid(), held.seatIds(), queueToken)));
        assertThat(byGate.getCode()).isEqualTo(ErrorCode.SEAT_TAKEN);

        testRedis.clean(held.pid());
        String retryToken = fixtures.admit(held.pid(), other);
        ApiException byDb = catchApiException(() -> seatHoldService.hold(
                new CreateHoldCommand(other, held.pid(), held.seatIds(), retryToken)));
        assertThat(byDb.getCode()).isEqualTo(ErrorCode.SEAT_STATE_CHANGED);

        assertThat(holdExpiryTransaction.claimAndExpire(50))
                .noneMatch(expired -> expired.holdId() == held.holdId());
        assertThat(db.holdStatus(held.holdId())).isEqualTo("PAYING");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "HELD")
                .containsEntry("hold_id", held.holdId()).containsEntry("held_until", payDeadline));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE user_id = ?", Long.class, other))
                .isZero();
    }

    @Test
    @DisplayName("승인 직전 좌석 하나를 잃으면 환불로 넘기고 409 SEAT_LOST_REFUNDING이다")
    void seatLostWhileApproving() {
        Held held = fixtures.held(2);
        long kept = held.seatIds().get(0);
        long lost = held.seatIds().get(1);
        long thief = fixtures.newUser();
        CreateHoldResult thiefHold = seatHoldService.hold(
                new CreateHoldCommand(thief, held.pid(), List.of(db.seatIds(held.pid()).get(2)),
                fixtures.admit(held.pid(), thief)));
        stubPg.beforeApproveReturns(orderId ->
                jdbcTemplate.update("UPDATE seat SET hold_id = ? WHERE id = ?", thiefHold.holdId(), lost));
        String key = key();

        ApiException e = catchApiException(() -> paymentService.pay(command(held, key)));

        assertThat(e.getCode()).isEqualTo(ErrorCode.SEAT_LOST_REFUNDING);
        long paymentId = ((Number) e.getDetail().get("paymentId")).longValue();
        assertThat(db.holdStatus(held.holdId())).isEqualTo("REFUNDING");
        assertThat(db.seat(kept)).containsEntry("status", "AVAILABLE").containsEntry("hold_id", null)
                .containsEntry("held_until", null);
        assertThat(db.seat(lost)).containsEntry("status", "HELD").containsEntry("hold_id", thiefHold.holdId());
        Map<String, Object> payment = db.payment(paymentId);
        assertThat(payment).containsEntry("status", "APPROVED").containsEntry("next_check_at", null);
        assertThat(db.bookingCountOfHold(held.holdId())).isZero();
        assertThat(db.outboxCount("REFUND_REQUESTED", paymentId)).isEqualTo(1);
        JsonNode refund = jsonMapper.readTree((String) db.outboxTask("REFUND_REQUESTED", paymentId).get("payload"));
        assertThat(refund.get("paymentId").asLong()).isEqualTo(paymentId);
        assertThat(refund.get("orderId").asString()).isEqualTo(payment.get("order_id"));

        assertThat(redis.hasKey(RedisKeys.userHold(held.userId(), held.pid()))).isFalse();
        assertThat(redis.hasKey(RedisKeys.seat(held.pid(), kept))).isFalse();
        assertThat(redis.hasKey(RedisKeys.seat(held.pid(), lost))).isFalse();
        assertThat(redis.opsForValue().get(RedisKeys.seat(held.pid(), db.seatIds(held.pid()).get(2))))
                .isEqualTo(db.holdToken(thiefHold.holdId()));

        ApiException again = catchApiException(() -> paymentService.pay(command(held, key)));
        assertThat(again.getCode()).isEqualTo(ErrorCode.SEAT_LOST_REFUNDING);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("결과 모름 결제를 취소하면 선점이 풀리고 다시 취소해도 바뀌지 않는다")
    void cancelAndRelease() {
        Held held = fixtures.held(2);
        stubPg.approveWith(PgResult.unknown("timeout"));
        String key = key();
        long paymentId = paymentService.pay(command(held, key)).paymentId();

        PaymentSettlement first = paymentTransaction.cancelAndRelease(paymentId);
        PaymentSettlement second = paymentTransaction.cancelAndRelease(paymentId);

        assertThat(first.changedSeats()).isTrue();
        assertThat(first.result().outcome()).isEqualTo(PaymentOutcome.CANCELLED);
        GateHold released = first.gateReflection().orElseThrow().gate();
        assertThat(released.seatIds()).containsExactlyElementsOf(held.seatIds());
        assertThat(released.holdToken()).isEqualTo(held.holdToken());
        assertThat(second.changedSeats()).isFalse();
        assertThat(second.result().outcome()).isEqualTo(PaymentOutcome.CANCELLED);
        assertThat(db.payment(paymentId)).containsEntry("status", "CANCELLED").containsEntry("next_check_at", null);
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        held.seatIds().forEach(seatId -> assertThat(db.seat(seatId)).containsEntry("status", "AVAILABLE")
                .containsEntry("hold_id", null));

        ApiException again = catchApiException(() -> paymentService.pay(command(held, key)));
        assertThat(again.getCode()).isEqualTo(ErrorCode.PAYMENT_CANCELLED);
        assertThat(again.getDetail()).containsEntry("paymentId", paymentId);
        assertThat(stubPg.approveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 끝난 결제는 확정·거절·결과 모름을 다시 불러도 바뀌지 않는다")
    void settledPaymentIsNotChangedAgain() {
        Held held = fixtures.held(1);
        PaymentResult confirmed = paymentService.pay(command(held, key()));
        long paymentId = confirmed.paymentId();

        PaymentSettlement decline = paymentTransaction.decline(paymentId);
        PaymentSettlement confirmAgain = paymentTransaction.confirm(paymentId, "other-tx");
        PaymentSettlement unknown = paymentTransaction.markUnknown(paymentId, "late timeout");

        assertThat(List.of(decline, confirmAgain, unknown)).allSatisfy(s -> {
            assertThat(s.changedSeats()).isFalse();
            assertThat(s.result().outcome()).isEqualTo(PaymentOutcome.CONFIRMED);
            assertThat(s.result().bookingId()).isEqualTo(confirmed.bookingId());
            assertThat(s.result().bookingNumber()).isEqualTo(confirmed.bookingNumber());
        });
        assertThat(db.payment(paymentId)).containsEntry("status", "APPROVED").containsEntry("last_error", null)
                .containsEntry("pg_tx_id", "stub-tx-" + db.payment(paymentId).get("order_id"));
        assertThat(db.holdStatus(held.holdId())).isEqualTo("CONFIRMED");
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 유니크 위반은 제약 이름으로 선점 중복과 키 중복을 구분한다")
    void violatedConstraintNames() {
        long holdId = -Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) - 1;
        long userId = fixtures.newUser();
        String key = key();
        List<Long> created = new ArrayList<>();
        try {
            created.add(paymentRepository.save(Payment.requested(holdId, userId, key, orderId(), 1000,
                    null)).getId());

            DataIntegrityViolationException sameHold = catchDuplicate(() -> paymentRepository.save(
                    Payment.requested(holdId, userId, key(), orderId(), 1000, null)));
            DataIntegrityViolationException sameKey = catchDuplicate(() -> paymentRepository.save(
                    Payment.requested(holdId - 1, userId, key, orderId(), 1000, null)));

            assertThat(ConstraintViolations.matches(sameHold, Payment.UK_HOLD)).isTrue();
            assertThat(ConstraintViolations.matches(sameHold, Payment.UK_IDEMPOTENCY)).isFalse();
            assertThat(ConstraintViolations.matches(sameKey, Payment.UK_IDEMPOTENCY)).isTrue();
            assertThat(ConstraintViolations.matches(sameKey, Payment.UK_HOLD)).isFalse();
        } finally {
            jdbcTemplate.update("DELETE FROM booking_payment WHERE user_id = ?", userId);
        }
    }

    @Test
    @DisplayName("게이트가 꺼져 있으면 결제는 Redis를 부르지 않는다")
    void gateOffSkipsRedis() {
        SeatGateRedisStore gateStore = mock(SeatGateRedisStore.class);
        PaymentService service = services.paymentService(gateStore,
                TestProperties.from(properties).gateEnabled(false).build());
        Held confirmed = fixtures.held(1);
        Held declined = fixtures.held(1);
        Held unknown = fixtures.held(1);

        assertThat(service.pay(command(confirmed, key())).outcome())
                .isEqualTo(PaymentOutcome.CONFIRMED);
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));
        assertThat(catchApiException(() -> service.pay(command(declined, key()))).getCode())
                .isEqualTo(ErrorCode.PAYMENT_DECLINED);
        stubPg.approveWith(PgResult.unknown("timeout"));
        assertThat(service.pay(command(unknown, key())).outcome())
                .isEqualTo(PaymentOutcome.UNKNOWN);

        verifyNoInteractions(gateStore);
        assertThat(db.holdStatus(confirmed.holdId())).isEqualTo("CONFIRMED");
        assertThat(db.holdStatus(declined.holdId())).isEqualTo("RELEASED");
        assertThat(db.holdStatus(unknown.holdId())).isEqualTo("PAYING");
        // 게이트 ON으로 잡은 선점 키는 결제가 건드리지 않았으므로 처음 TTL(선점 5분) 안에 그대로 있다
        assertThat(testRedis.pttl(RedisKeys.seat(confirmed.pid(), confirmed.seatIds().get(0))))
                .isBetween(1L, properties.hold().ttl().toMillis());
    }

    private static DataIntegrityViolationException catchDuplicate(Runnable action) {
        try {
            action.run();
        } catch (DataIntegrityViolationException e) {
            return e;
        }
        throw new AssertionError("DataIntegrityViolationException이 나야 한다");
    }

    private static PayCommand command(Held held, String key) {
        return new PayCommand(held.userId(), held.holdId(), key, Optional.empty());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String orderId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
