package com.example.ticketing.booking.payment;

import com.example.ticketing.booking.hold.domain.HoldStatus;
import com.example.ticketing.booking.payment.domain.BookingPaymentStatus;
import java.time.Instant;
import java.util.List;

// applied는 이번 호출이 상태를 바꿨는지다. 바꾼 쪽만 Redis에 반영한다
// seatIds는 applied일 때 이 hold가 쥐고 있던 좌석, gateCloseAt은 확정했을 때만 있다
public record PaymentSettlement(
        long paymentId,
        PaymentOutcome outcome,
        boolean applied,
        BookingPaymentStatus paymentStatus,
        HoldStatus holdStatus,
        Long bookingId,
        String bookingNumber,
        long holdId,
        long performanceId,
        long userId,
        String holdToken,
        List<Long> seatIds,
        Instant gateCloseAt
) {
}
