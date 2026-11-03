package com.example.ticketing.payment.service;

import com.example.ticketing.booking.dto.IssueBookingCommand;
import com.example.ticketing.booking.dto.IssuedBooking;
import com.example.ticketing.booking.service.BookingIssuer;
import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.text.Texts;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.RefundRequestedPayload;
import com.example.ticketing.outbox.service.OutboxWriter;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.payment.dto.PaymentStart;
import com.example.ticketing.payment.dto.PaymentStart.Existing;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.payment.repository.PaymentRepository;
import com.example.ticketing.seat.domain.Seat;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 락 순서: seat_hold → booking_payment → seat(id 오름차순). 잠근 행은 엔티티 메서드로 바꾼다. 이 안에서는 PG·Redis를 부르지 않는다
@Component
public class PaymentTransaction {

    private final PaymentRepository paymentRepository;
    private final PaymentReader paymentReader;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final SeatCatalog seatCatalog;
    private final BookingIssuer bookingIssuer;
    private final OutboxWriter outboxWriter;
    private final DbClock dbClock;
    private final Clock clock;
    private final Duration payDeadline;
    private final Duration requestedRecheckAfter;
    private final long unknownRecheckNanos;

    public PaymentTransaction(PaymentRepository paymentRepository, PaymentReader paymentReader,
                              SeatHoldRepository seatHoldRepository, SeatRepository seatRepository,
                              SeatCatalog seatCatalog, BookingIssuer bookingIssuer, OutboxWriter outboxWriter,
                              DbClock dbClock, Clock clock, TicketingProperties properties) {
        this.paymentRepository = paymentRepository;
        this.paymentReader = paymentReader;
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.seatCatalog = seatCatalog;
        this.bookingIssuer = bookingIssuer;
        this.outboxWriter = outboxWriter;
        this.dbClock = dbClock;
        this.clock = clock;
        this.payDeadline = properties.payment().payDeadline();
        this.requestedRecheckAfter = properties.payment().requestedRecheckAfter();
        this.unknownRecheckNanos = properties.payment().unknownRecheckAfter().toNanos();
    }

    // hold 행 락이 같은 hold의 결제 시작을 줄 세운다. READ_COMMITTED라 기다린 동안 커밋된 결제가 락 뒤의 findByHoldId에서 보인다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentStart start(long userId, long holdId, String idempotencyKey) {
        Optional<Payment> sameKey = paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (sameKey.isPresent()) {
            return new Existing(paymentReader.resultOf(sameKey.get()));
        }

        SeatHold hold = seatHoldRepository.lockByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));

        Optional<Payment> sameHold = paymentRepository.findByHoldId(holdId);
        if (sameHold.isPresent()) {
            return PaymentStart.onSameHold(sameHold.get().getIdempotencyKey().equals(idempotencyKey),
                    paymentReader.resultOf(sameHold.get(), hold));
        }

        LocalDateTime now = dbClock.now();
        requirePayable(hold, now);
        LocalDateTime deadline = now.plus(payDeadline);
        heldToPaying(hold, deadline, now);
        List<Long> seatIds = seatRepository.findHeldSeatIds(holdId);

        String orderId = UUID.randomUUID().toString().replace("-", "");
        Payment payment = paymentRepository.save(Payment.requested(holdId, userId, idempotencyKey,
                orderId, hold.getTotalAmount(), now.plus(requestedRecheckAfter)));
        return new Started(payment.getId(), orderId, hold.getTotalAmount(), gateOf(hold, seatIds), deadline, now);
    }

    // 요청 스레드와 대사가 동시에 불러도 hold 락 뒤 PAYING 확인으로 한 번만 효과가 있다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement openToApproved(long paymentId, String pgTxId) {
        Locked locked = lock(paymentId);
        if (!locked.paying()) {
            return PaymentSettlement.seatsUnchanged(paymentReader.resultOf(locked.payment(), locked.hold()));
        }
        SeatHold hold = locked.hold();
        long holdId = hold.getId();
        List<Seat> seats = seatRepository.lockHeldSeats(holdId);
        List<Long> seatIds = idsOf(seats);
        LocalDateTime now = dbClock.now();
        if (seats.size() != hold.getSeatCount()) {
            return payingToRefunding(locked, pgTxId, seats);
        }

        seats.forEach(Seat::heldToSold);
        hold.payingToConfirmed();
        locked.payment().openToApproved(pgTxId);
        IssuedBooking booking = bookingIssuer.issue(new IssueBookingCommand(hold.getUserId(),
                hold.getPerformanceId(), holdId, paymentId, hold.getTotalAmount(),
                seats.stream().map(Seat::info).toList(), now));
        // 서버와 DB의 시간대가 달라도 맞도록 DB 시각 기준 남은 시간으로 바꾼다
        LocalDateTime gateCloseAt = seatCatalog.performance(hold.getPerformanceId()).gateCloseAt();
        Instant gateCloseInstant = clock.instant().plus(Duration.between(now, gateCloseAt));
        return PaymentSettlement.seatsChanged(new PaymentResult(paymentId, PaymentOutcome.CONFIRMED,
                        PaymentStatus.APPROVED, HoldStatus.CONFIRMED, booking.bookingId(), booking.bookingNumber()),
                gateOf(hold, seatIds), gateCloseInstant);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement openToDeclined(long paymentId) {
        return payingToReleased(paymentId, PaymentStatus.DECLINED);
    }

    // 망취소가 PG에서 확정된 뒤 대사가 부른다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement openToCancelled(long paymentId) {
        return payingToReleased(paymentId, PaymentStatus.CANCELLED);
    }

    // 결과를 모를 때는 hold·좌석을 그대로 두고 대사가 조회로 확정하게 한다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSettlement requestedToUnknown(long paymentId, String detail) {
        paymentRepository.requestedToUnknown(paymentId, Texts.abbreviate(detail, Payment.MAX_LAST_ERROR),
                unknownRecheckNanos);
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
        return PaymentSettlement.seatsUnchanged(paymentReader.resultOf(payment));
    }

    // PG 환불 확정 뒤 RefundRequestedHandler가 부른다. 다시 불려도 락 뒤 상태를 보고 두 번째는 아무것도 바꾸지 않는다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void approvedToCancelled(long paymentId) {
        Locked locked = lock(paymentId);
        // 예매 취소의 환불은 hold가 CONFIRMED라 hold를 그대로 둔다
        if (locked.hold().getStatus() == HoldStatus.REFUNDING) {
            locked.hold().refundingToReleased();
        }
        if (locked.payment().getStatus() == PaymentStatus.APPROVED) {
            locked.payment().approvedToCancelled();
        }
    }

    // 만료 판정은 DB 시각이다. 만료 스케줄러는 이 hold 행을 잠근 동안 집지 못한다
    private static void requirePayable(SeatHold hold, LocalDateTime dbNow) {
        long holdId = hold.getId();
        if (hold.getStatus() == HoldStatus.EXPIRED) {
            throw new ApiException(ErrorCode.HOLD_EXPIRED, Map.of("holdId", holdId));
        }
        if (hold.getStatus() != HoldStatus.HELD) {
            throw new ApiException(ErrorCode.HOLD_NOT_PAYABLE,
                    Map.of("holdId", holdId, "status", hold.getStatus().name()));
        }
        if (hold.isExpiredAt(dbNow)) {
            throw new ApiException(ErrorCode.HOLD_EXPIRED, Map.of("holdId", holdId));
        }
    }

    // 좌석을 결제 마감까지 붙잡는다. 좌석은 잠그지 않아 기한이 지난 좌석을 남이 잡을 수 있으므로 조건부 UPDATE의 행 수로 본다
    private void heldToPaying(SeatHold hold, LocalDateTime deadline, LocalDateTime dbNow) {
        long holdId = hold.getId();
        hold.heldToPaying(deadline, dbNow);
        if (seatRepository.extendHeldUntil(holdId, deadline) != hold.getSeatCount()) {
            throw new ApiException(ErrorCode.SEAT_STATE_CHANGED, Map.of("holdId", holdId));
        }
    }

    // 결제 마감이 지나 남이 좌석을 가져갔다. 돈은 받았으므로 남은 좌석을 풀고 환불을 맡긴다
    private PaymentSettlement payingToRefunding(Locked locked, String pgTxId, List<Seat> seats) {
        SeatHold hold = locked.hold();
        Payment payment = locked.payment();
        long paymentId = payment.getId();
        List<Long> seatIds = idsOf(seats);
        seats.forEach(Seat::heldToAvailable);
        hold.payingToRefunding();
        payment.openToApproved(pgTxId);
        outboxWriter.write(OutboxTaskType.REFUND_REQUESTED, paymentId,
                new RefundRequestedPayload(paymentId, payment.getOrderId()));
        return PaymentSettlement.seatsChanged(new PaymentResult(paymentId, PaymentOutcome.SEAT_LOST,
                PaymentStatus.APPROVED, HoldStatus.REFUNDING, null, null), gateOf(hold, seatIds), null);
    }

    private PaymentSettlement payingToReleased(long paymentId, PaymentStatus target) {
        Locked locked = lock(paymentId);
        SeatHold hold = locked.hold();
        if (!locked.paying()) {
            return PaymentSettlement.seatsUnchanged(paymentReader.resultOf(locked.payment(), hold));
        }
        List<Seat> seats = seatRepository.lockHeldSeats(hold.getId());
        seats.forEach(Seat::heldToAvailable);
        hold.payingToReleased();
        if (target == PaymentStatus.DECLINED) {
            locked.payment().openToDeclined();
        } else {
            locked.payment().openToCancelled();
        }
        return PaymentSettlement.seatsChanged(new PaymentResult(paymentId,
                PaymentOutcome.of(target, HoldStatus.RELEASED), target, HoldStatus.RELEASED, null, null),
                gateOf(hold, idsOf(seats)), null);
    }

    // 결제 엔티티를 읽기 전에 hold를 먼저 잠근다. 결과 모름 전이는 hold를 잠그지 않으므로 결제 행도 잠가 락 뒤의 최신 상태를 본다
    private Locked lock(long paymentId) {
        long holdId = paymentRepository.findHoldIdById(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
        SeatHold hold = seatHoldRepository.lockById(holdId)
                .orElseThrow(() -> new IllegalStateException("결제 " + paymentId + "의 hold " + holdId + "가 없다"));
        Payment payment = paymentRepository.lockById(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
        return new Locked(hold, payment);
    }

    private static List<Long> idsOf(List<Seat> seats) {
        return seats.stream().map(Seat::getId).toList();
    }

    private static GateHold gateOf(SeatHold hold, List<Long> seatIds) {
        return new GateHold(hold.getUserId(), hold.getPerformanceId(), hold.getHoldToken(), seatIds);
    }

    private static ApiException paymentNotFound(long paymentId) {
        return new ApiException(ErrorCode.PAYMENT_NOT_FOUND, Map.of("paymentId", paymentId));
    }

    private record Locked(SeatHold hold, Payment payment) {

        boolean paying() {
            return hold.getStatus() == HoldStatus.PAYING && payment.getStatus().isOpen();
        }
    }
}
