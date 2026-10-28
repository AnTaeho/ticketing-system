package com.example.ticketing.payment.service;

import com.example.ticketing.booking.dto.IssuedBooking;
import com.example.ticketing.booking.service.BookingIssuer;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentStart;
import com.example.ticketing.payment.dto.PaymentStart.Existing;
import com.example.ticketing.payment.repository.PaymentRepository;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentReader {

    private final PaymentRepository paymentRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final BookingIssuer bookingIssuer;

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PaymentResult find(long userId, long paymentId) {
        Payment payment = paymentRepository.findByIdAndUserId(paymentId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_NOT_FOUND, Map.of("paymentId", paymentId)));
        return resultOf(payment);
    }

    // 멱등키 유니크 위반 뒤 새 트랜잭션으로 다시 읽는다. 위반한 쪽 결제가 이제는 커밋돼 보인다
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<PaymentStart> findSameKeyAfterDuplicate(long userId, String idempotencyKey) {
        return paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(payment -> new Existing(resultOf(payment)));
    }

    // hold 유니크 위반 뒤 새 트랜잭션으로 다시 읽는다
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<PaymentStart> findSameHoldAfterDuplicate(long userId, long holdId, String idempotencyKey) {
        return paymentRepository.findByHoldId(holdId)
                .filter(payment -> payment.getUserId() == userId)
                .map(payment -> PaymentStart.onSameHold(payment.getIdempotencyKey().equals(idempotencyKey),
                        resultOf(payment)));
    }

    // 호출자의 트랜잭션 안에서 부른다
    public PaymentResult resultOf(Payment payment) {
        SeatHold hold = seatHoldRepository.findById(payment.getHoldId())
                .orElseThrow(() -> new IllegalStateException("결제 " + payment.getId() + "의 hold가 없다"));
        return resultOf(payment, hold);
    }

    public PaymentResult resultOf(Payment payment, SeatHold hold) {
        PaymentOutcome outcome = PaymentOutcome.of(payment.getStatus(), hold.getStatus());
        Optional<IssuedBooking> booking = outcome == PaymentOutcome.CONFIRMED
                ? bookingIssuer.findByHold(hold.getId())
                : Optional.empty();
        return new PaymentResult(payment.getId(), outcome, payment.getStatus(), hold.getStatus(),
                booking.map(IssuedBooking::bookingId).orElse(null),
                booking.map(IssuedBooking::bookingNumber).orElse(null));
    }
}
