package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.Booking;
import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.dto.CancelResult;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.service.SeatGateReflector;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. PG 환불과 게이트 키 해제는 DB 트랜잭션 밖에서 한다
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingReader bookingReader;
    private final PaymentRefunder paymentRefunder;
    private final BookingTransaction bookingTransaction;
    private final SeatGateReflector gate;

    // PG 환불을 먼저 한다. 환불만 끝나고 DB 변경이 실패해도 다시 누르면 환불된 결제는 PG와 시작 확인 없이 DB만 바꿔 끝난다
    public CancelResult cancel(long userId, long bookingId) {
        Booking booking = bookingReader.getBooking(userId, bookingId);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return new CancelResult(bookingId, BookingStatus.CANCELLED);
        }
        if (!paymentRefunder.isRefunded(booking.getPaymentId())) {
            if (bookingReader.hasStarted(booking.getPerformanceId())) {
                throw new ApiException(ErrorCode.CANCEL_NOT_ALLOWED, Map.of("bookingId", bookingId));
            }
            if (!paymentRefunder.refund(booking.getPaymentId())) {
                throw new ApiException(ErrorCode.REFUND_FAILED, Map.of("bookingId", bookingId));
            }
        }
        bookingTransaction.confirmedToCancelled(userId, bookingId).ifPresent(gate::release);
        return new CancelResult(bookingId, BookingStatus.CANCELLED);
    }
}
