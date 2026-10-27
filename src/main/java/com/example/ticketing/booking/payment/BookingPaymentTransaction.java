package com.example.ticketing.booking.payment;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.hold.SeatHoldRepository;
import com.example.ticketing.booking.hold.domain.HoldStatus;
import com.example.ticketing.booking.hold.domain.SeatHold;
import com.example.ticketing.booking.outbox.OutboxWriter;
import com.example.ticketing.booking.outbox.domain.OutboxTaskType;
import com.example.ticketing.booking.payment.StartResult.Existing;
import com.example.ticketing.booking.payment.StartResult.Started;
import com.example.ticketing.booking.payment.domain.BookingPayment;
import com.example.ticketing.booking.payment.domain.BookingPaymentStatus;
import com.example.ticketing.booking.purchase.BookingRepository;
import com.example.ticketing.booking.purchase.BookingSeatRepository;
import com.example.ticketing.booking.purchase.domain.Booking;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.seat.SeatRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 락 순서: seat_hold → seat. 이 안에서는 PG·Redis를 부르지 않는다
@Component
@RequiredArgsConstructor
public class BookingPaymentTransaction {

    private static final DateTimeFormatter BOOKING_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_LAST_ERROR = 200;

    private final BookingPaymentRepository paymentRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final OutboxWriter outboxWriter;
    private final SeatCatalog seatCatalog;
    private final DbClock dbClock;
    private final BookingProperties properties;
    private final JsonMapper jsonMapper;
    private final EntityManager entityManager;

    // hold 행 락이 같은 hold의 결제 시작을 줄 세운다. READ_COMMITTED라 기다린 동안 커밋된 결제가 3단계에서 보인다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StartResult start(long userId, long holdId, String idempotencyKey) {
        forgetEarlierEntities();
        Optional<BookingPayment> sameKey = paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (sameKey.isPresent()) {
            return new Existing(current(sameKey.get()));
        }

        SeatHold hold = seatHoldRepository.lockByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new BookingException(BookingErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));

        Optional<BookingPayment> sameHold = paymentRepository.findByHoldId(holdId);
        if (sameHold.isPresent()) {
            if (sameHold.get().getIdempotencyKey().equals(idempotencyKey)) {
                return new Existing(current(sameHold.get(), hold));
            }
            throw otherPaymentOnHold(sameHold.get(), hold);
        }

        if (hold.getStatus() == HoldStatus.EXPIRED) {
            throw new BookingException(BookingErrorCode.HOLD_EXPIRED, Map.of("holdId", holdId));
        }
        if (hold.getStatus() != HoldStatus.HELD) {
            throw new BookingException(BookingErrorCode.HOLD_NOT_PAYABLE,
                    Map.of("holdId", holdId, "status", hold.getStatus().name()));
        }
        LocalDateTime now = dbClock.now();
        if (!hold.getExpiresAt().isAfter(now)) {
            throw new BookingException(BookingErrorCode.HOLD_EXPIRED, Map.of("holdId", holdId));
        }

        LocalDateTime payDeadline = now.plus(properties.payment().payDeadline());
        if (seatHoldRepository.markPaying(holdId, payDeadline) != 1) {
            throw new BookingException(BookingErrorCode.HOLD_EXPIRED, Map.of("holdId", holdId));
        }
        if (seatRepository.extendHeldUntil(holdId, payDeadline) != hold.getSeatCount()) {
            throw new BookingException(BookingErrorCode.SEAT_STATE_CHANGED, Map.of("holdId", holdId));
        }
        List<Long> seatIds = seatRepository.findHeldSeatIds(holdId);

        String orderId = UUID.randomUUID().toString().replace("-", "");
        BookingPayment payment = paymentRepository.save(BookingPayment.requested(holdId, userId, idempotencyKey,
                orderId, hold.getTotalAmount(), now.plus(properties.payment().requestedRecheckAfter())));
        return new Started(payment.getId(), holdId, hold.getPerformanceId(), userId, orderId,
                hold.getTotalAmount(), hold.getHoldToken(), List.copyOf(seatIds), payDeadline, now);
    }

    // 요청 스레드와 대사가 동시에 불러도 hold 락 뒤 PAYING 확인으로 한 번만 효과가 있다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement confirm(long paymentId, String pgTxId) {
        forgetEarlierEntities();
        Locked locked = lock(paymentId);
        SeatHold hold = locked.hold();
        BookingPayment payment = locked.payment();
        if (!isPaying(hold, payment)) {
            return current(payment, hold);
        }
        long holdId = hold.getId();
        List<Long> seatIds = seatRepository.lockHeldSeatIds(holdId);
        LocalDateTime now = dbClock.now();

        if (seatIds.size() == hold.getSeatCount()) {
            expect(seatRepository.markSold(holdId), seatIds.size(), "seat SOLD");
            expect(seatHoldRepository.markConfirmed(holdId), 1, "hold CONFIRMED");
            expect(paymentRepository.markApproved(paymentId, pgTxId), 1, "payment APPROVED");
            Booking booking = bookingRepository.save(Booking.confirmed(bookingNumber(now, holdId), holdId,
                    paymentId, hold.getUserId(), hold.getPerformanceId(), hold.getTotalAmount()));
            expect(bookingSeatRepository.copySoldSeats(booking.getId(), holdId), seatIds.size(), "booking_seat");
            outboxWriter.write(OutboxTaskType.TICKET_ISSUED, booking.getId(), json(
                    "bookingId", booking.getId(), "userId", hold.getUserId(),
                    "performanceId", hold.getPerformanceId()));
            // 서버와 DB의 시간대가 달라도 맞도록 DB 시각 기준 남은 시간으로 바꾼다
            LocalDateTime gateCloseAt = seatCatalog.performance(hold.getPerformanceId()).gateCloseAt();
            Instant gateCloseInstant = Instant.now().plus(Duration.between(now, gateCloseAt));
            return applied(payment, hold, PaymentOutcome.CONFIRMED, BookingPaymentStatus.APPROVED,
                    HoldStatus.CONFIRMED, booking, seatIds, gateCloseInstant);
        }

        // 결제 마감이 지나 남이 좌석을 가져갔다. 돈은 받았으므로 남은 좌석을 풀고 환불을 맡긴다
        expect(seatRepository.releaseHeldSeats(holdId), seatIds.size(), "seat AVAILABLE");
        expect(seatHoldRepository.markRefunding(holdId), 1, "hold REFUNDING");
        expect(paymentRepository.markApproved(paymentId, pgTxId), 1, "payment APPROVED");
        outboxWriter.write(OutboxTaskType.REFUND_REQUESTED, paymentId, json(
                "paymentId", paymentId, "orderId", payment.getOrderId()));
        return applied(payment, hold, PaymentOutcome.SEAT_LOST, BookingPaymentStatus.APPROVED,
                HoldStatus.REFUNDING, null, seatIds, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement decline(long paymentId) {
        forgetEarlierEntities();
        return releaseAfterPg(paymentId, BookingPaymentStatus.DECLINED);
    }

    // 망취소가 PG에서 확정된 뒤 대사가 부른다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement cancelAndRelease(long paymentId) {
        forgetEarlierEntities();
        return releaseAfterPg(paymentId, BookingPaymentStatus.CANCELLED);
    }

    // 결과를 모를 때는 hold·좌석을 그대로 두고 대사가 조회로 확정하게 한다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement markUnknown(long paymentId, String detail) {
        forgetEarlierEntities();
        long recheckMicros = properties.payment().unknownRecheckAfter().toNanos() / 1_000;
        paymentRepository.markUnknown(paymentId, abbreviate(detail), recheckMicros);
        BookingPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> paymentNotFound(paymentId));
        return current(payment);
    }

    // PG 환불 확정 뒤 Outbox 워커가 부른다. 다시 불려도 조건부 전이라 두 번째는 바꾸는 행이 없다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean completeRefund(long paymentId) {
        forgetEarlierEntities();
        long holdId = paymentRepository.findHoldIdById(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
        seatHoldRepository.markReleasedFromRefunding(holdId);
        return paymentRepository.markRefunded(paymentId) == 1;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement view(long userId, long paymentId) {
        forgetEarlierEntities();
        BookingPayment payment = paymentRepository.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> paymentNotFound(paymentId));
        return current(payment);
    }

    // 유니크 위반 뒤 새 트랜잭션으로 다시 읽는다. 위반한 쪽 결제가 이제는 커밋돼 보인다
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<StartResult> findAfterDuplicate(long userId, long holdId, String idempotencyKey,
                                                    boolean idempotencyViolated) {
        forgetEarlierEntities();
        if (idempotencyViolated) {
            return paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .map(payment -> new Existing(current(payment)));
        }
        Optional<BookingPayment> sameHold = paymentRepository.findByHoldId(holdId)
                .filter(payment -> payment.getUserId() == userId);
        if (sameHold.isEmpty()) {
            return Optional.empty();
        }
        if (sameHold.get().getIdempotencyKey().equals(idempotencyKey)) {
            return Optional.of(new Existing(current(sameHold.get())));
        }
        SeatHold hold = seatHoldRepository.findById(holdId)
                .orElseThrow(() -> new IllegalStateException("결제 " + sameHold.get().getId() + "의 hold가 없다"));
        throw otherPaymentOnHold(sameHold.get(), hold);
    }

    // 다른 키의 결제가 이미 끝나 자리가 풀렸으면 다시 고르게 하고, 아직 진행 중이면 다른 화면에서 결제 중이라고 알린다
    private static BookingException otherPaymentOnHold(BookingPayment other, SeatHold hold) {
        if (other.getStatus() == BookingPaymentStatus.DECLINED || other.getStatus() == BookingPaymentStatus.CANCELLED) {
            return new BookingException(BookingErrorCode.HOLD_NOT_PAYABLE,
                    Map.of("holdId", hold.getId(), "status", hold.getStatus().name()));
        }
        return new BookingException(BookingErrorCode.ALREADY_PAYING, Map.of("holdId", hold.getId()));
    }

    private PaymentSettlement releaseAfterPg(long paymentId, BookingPaymentStatus target) {
        Locked locked = lock(paymentId);
        SeatHold hold = locked.hold();
        BookingPayment payment = locked.payment();
        if (!isPaying(hold, payment)) {
            return current(payment, hold);
        }
        long holdId = hold.getId();
        List<Long> seatIds = seatRepository.lockHeldSeatIds(holdId);
        expect(seatRepository.releaseHeldSeats(holdId), seatIds.size(), "seat AVAILABLE");
        expect(seatHoldRepository.markReleasedFromPaying(holdId), 1, "hold RELEASED");
        int updated = target == BookingPaymentStatus.DECLINED
                ? paymentRepository.markDeclined(paymentId)
                : paymentRepository.markCancelled(paymentId);
        expect(updated, 1, "payment " + target);
        PaymentOutcome outcome = target == BookingPaymentStatus.DECLINED
                ? PaymentOutcome.DECLINED
                : PaymentOutcome.CANCELLED;
        return applied(payment, hold, outcome, target, HoldStatus.RELEASED, null, seatIds, null);
    }

    // 결제 엔티티를 읽기 전에 hold를 먼저 잠가야 락 뒤의 최신 결제 상태를 본다
    private Locked lock(long paymentId) {
        long holdId = paymentRepository.findHoldIdById(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
        SeatHold hold = seatHoldRepository.lockById(holdId)
                .orElseThrow(() -> new IllegalStateException("결제 " + paymentId + "의 hold " + holdId + "가 없다"));
        BookingPayment payment = paymentRepository.findById(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
        return new Locked(hold, payment);
    }

    // open-in-view면 한 요청의 트랜잭션들이 영속성 컨텍스트를 같이 써서 앞 트랜잭션이 읽은 엔티티가 남는다.
    // 락 뒤에 최신 행을 읽도록 트랜잭션마다 비우고 시작한다
    private void forgetEarlierEntities() {
        entityManager.clear();
    }

    private static boolean isPaying(SeatHold hold, BookingPayment payment) {
        return hold.getStatus() == HoldStatus.PAYING
                && (payment.getStatus() == BookingPaymentStatus.REQUESTED
                || payment.getStatus() == BookingPaymentStatus.UNKNOWN);
    }

    private PaymentSettlement current(BookingPayment payment) {
        SeatHold hold = seatHoldRepository.findById(payment.getHoldId())
                .orElseThrow(() -> new IllegalStateException("결제 " + payment.getId() + "의 hold가 없다"));
        return current(payment, hold);
    }

    private PaymentSettlement current(BookingPayment payment, SeatHold hold) {
        PaymentOutcome outcome = PaymentOutcome.of(payment.getStatus(), hold.getStatus());
        Booking booking = outcome == PaymentOutcome.CONFIRMED
                ? bookingRepository.findByHoldId(hold.getId()).orElse(null)
                : null;
        return new PaymentSettlement(payment.getId(), outcome, false, payment.getStatus(), hold.getStatus(),
                booking == null ? null : booking.getId(), booking == null ? null : booking.getBookingNumber(),
                hold.getId(), hold.getPerformanceId(), hold.getUserId(), hold.getHoldToken(), List.of(), null);
    }

    private static PaymentSettlement applied(BookingPayment payment, SeatHold hold, PaymentOutcome outcome,
                                             BookingPaymentStatus paymentStatus, HoldStatus holdStatus,
                                             Booking booking, List<Long> seatIds, Instant gateCloseAt) {
        return new PaymentSettlement(payment.getId(), outcome, true, paymentStatus, holdStatus,
                booking == null ? null : booking.getId(), booking == null ? null : booking.getBookingNumber(),
                hold.getId(), hold.getPerformanceId(), hold.getUserId(), hold.getHoldToken(),
                List.copyOf(seatIds), gateCloseAt);
    }

    static String bookingNumber(LocalDateTime dbNow, long holdId) {
        return "B" + dbNow.format(BOOKING_DATE) + "-" + String.format("%06d", holdId);
    }

    private String json(Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            payload.put((String) keyValues[i], keyValues[i + 1]);
        }
        return jsonMapper.writeValueAsString(payload);
    }

    // hold 락 아래라 어긋날 수 없다. 어긋나면 롤백해 반쯤 바뀐 상태를 남기지 않는다
    private static void expect(int actual, int expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException(what + " 영향 행 수: 기대 " + expected + ", 실제 " + actual);
        }
    }

    static String abbreviate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= MAX_LAST_ERROR ? detail : detail.substring(0, MAX_LAST_ERROR - 1) + "…";
    }

    private static BookingException paymentNotFound(long paymentId) {
        return new BookingException(BookingErrorCode.PAYMENT_NOT_FOUND, Map.of("paymentId", paymentId));
    }

    private record Locked(SeatHold hold, BookingPayment payment) {
    }
}
