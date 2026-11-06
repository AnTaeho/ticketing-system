package com.example.ticketing.payment.service;

import com.example.ticketing.booking.dto.IssueBookingCommand;
import com.example.ticketing.booking.dto.IssuedBooking;
import com.example.ticketing.booking.service.BookingIssuer;
import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.jdbc.RowCounts;
import com.example.ticketing.common.text.Texts;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.payment.dto.PaymentStart;
import com.example.ticketing.payment.dto.PaymentStart.Existing;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.payment.repository.PaymentRepository;
import com.example.ticketing.seat.domain.Seat;
import com.example.ticketing.seat.repository.SeatRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 락 순서는 seat_hold → booking_payment → seat(id 오름차순)이다. 이 안에서는 PG·Redis를 부르지 않는다
// 잠근 행 하나는 엔티티 메서드로, 잠근 좌석 여러 개는 UPDATE 한 문장으로 바꾼다
@Component
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PaymentTransaction {

    private final PaymentRepository paymentRepository;
    private final PaymentReader paymentReader;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final BookingIssuer bookingIssuer;
    private final DbClock dbClock;
    private final Duration requestedRecheckAfter;
    private final long unknownRecheckNanos;

    public PaymentTransaction(PaymentRepository paymentRepository, PaymentReader paymentReader,
                              SeatHoldRepository seatHoldRepository, SeatRepository seatRepository,
                              BookingIssuer bookingIssuer,
                              DbClock dbClock, TicketingProperties properties) {
        this.paymentRepository = paymentRepository;
        this.paymentReader = paymentReader;
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.bookingIssuer = bookingIssuer;
        this.dbClock = dbClock;
        this.requestedRecheckAfter = properties.payment().requestedRecheckAfter();
        this.unknownRecheckNanos = properties.payment().unknownRecheckAfter().toNanos();
    }

    // hold 행 락이 같은 hold의 결제 시작을 줄 세운다. READ_COMMITTED라 기다린 동안 커밋된 결제가 락 뒤의 findByHoldId에서 보인다
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
        hold.heldToPaying(now);

        String orderId = UUID.randomUUID().toString().replace("-", "");
        Payment payment = paymentRepository.save(Payment.requested(holdId, userId, idempotencyKey,
                orderId, hold.getTotalAmount(), now, now.plus(requestedRecheckAfter)));
        return new Started(payment.getId(), orderId, hold.getTotalAmount());
    }

    // 요청 스레드와 대사가 동시에 불러도 hold 락 뒤 PAYING 확인으로 한 번만 효과가 있다
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

        hold.payingToConfirmed();
        locked.payment().openToApproved(pgTxId);
        // 좌석 UPDATE가 앞의 엔티티 변경을 flush한 뒤 영속성 컨텍스트를 비우므로 엔티티 변경 뒤에 부른다
        RowCounts.requireExactly(seatRepository.heldToSold(holdId), hold.getSeatCount(), "seat SOLD");
        IssuedBooking booking = bookingIssuer.issue(new IssueBookingCommand(hold.getUserId(),
                hold.getPerformanceId(), holdId, paymentId, hold.getTotalAmount(),
                seats.stream().map(Seat::info).toList(), now));
        return PaymentSettlement.seatsChanged(new PaymentResult(paymentId,
                        PaymentStatus.APPROVED, HoldStatus.CONFIRMED, booking.bookingId(), booking.bookingNumber()),
                gateOf(hold, seatIds));
    }

    public PaymentSettlement openToDeclined(long paymentId) {
        return payingToReleased(paymentId, PaymentStatus.DECLINED);
    }

    // 망취소가 PG에서 확정된 뒤 대사가 부른다
    public PaymentSettlement openToCancelled(long paymentId) {
        return payingToReleased(paymentId, PaymentStatus.CANCELLED);
    }

    // 결과를 모를 때는 hold·좌석을 그대로 두고 대사가 조회로 확정하게 한다
    public PaymentSettlement requestedToUnknown(long paymentId, String detail) {
        paymentRepository.requestedToUnknown(paymentId, Texts.abbreviate(detail, Payment.MAX_LAST_ERROR),
                unknownRecheckNanos);
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> paymentNotFound(paymentId));
        return PaymentSettlement.seatsUnchanged(paymentReader.resultOf(payment));
    }

    // PG 환불 확정 뒤 부른다. 다시 불려도 락 뒤 상태를 보고 두 번째는 아무것도 바꾸지 않는다
    public void approvedToCancelled(long paymentId) {
        Locked locked = lock(paymentId);
        // 예매 취소의 환불은 hold가 CONFIRMED라 hold를 그대로 둔다
        if (locked.payment().getStatus() == PaymentStatus.APPROVED) {
            locked.payment().approvedToCancelled();
        }
    }

    // 대사가 다음 확인 시각을 정한다. 끝난 결제는 건드리지 않는다. 바꿨으면 true
    public boolean reschedule(long paymentId, Duration delay, String lastError) {
        return paymentRepository.reschedule(paymentId, delay.toNanos(),
                Texts.abbreviate(lastError, Payment.MAX_LAST_ERROR)) == 1;
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

    private PaymentSettlement payingToReleased(long paymentId, PaymentStatus target) {
        Locked locked = lock(paymentId);
        SeatHold hold = locked.hold();
        if (!locked.paying()) {
            return PaymentSettlement.seatsUnchanged(paymentReader.resultOf(locked.payment(), hold));
        }
        List<Seat> seats = seatRepository.lockHeldSeats(hold.getId());
        hold.payingToReleased();
        if (target == PaymentStatus.DECLINED) {
            locked.payment().openToDeclined();
        } else {
            locked.payment().openToCancelled();
        }
        RowCounts.requireExactly(seatRepository.heldToAvailable(hold.getId()), seats.size(), "seat AVAILABLE");
        return PaymentSettlement.seatsChanged(new PaymentResult(paymentId,
                target, HoldStatus.RELEASED, null, null),
                gateOf(hold, idsOf(seats)));
    }

    // hold를 먼저 잠근 뒤 결제를 읽는다. 결과 모름 전이는 hold를 잠그지 않으므로 결제 행도 잠가 최신 상태를 본다
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
        return new GateHold(hold.getPerformanceId(), hold.getHoldToken(), seatIds);
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
