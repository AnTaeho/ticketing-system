package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.dto.CancelResult;
import com.example.ticketing.booking.dto.CancelTarget;
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

    // PG 환불을 먼저 한다. 같은 주문 취소는 PG가 같은 답을 주므로 중간에 실패해도 다시 누르면 끝난다
    public CancelResult cancel(long userId, long bookingId) {
        CancelTarget target = bookingReader.findCancelTarget(userId, bookingId);
        if (target.status() == BookingStatus.CANCELLED) {
            return new CancelResult(bookingId, BookingStatus.CANCELLED);
        }
        if (!target.notStarted()) {
            throw new ApiException(ErrorCode.CANCEL_NOT_ALLOWED, Map.of("bookingId", bookingId));
        }
        if (!paymentRefunder.refund(target.paymentId())) {
            throw new ApiException(ErrorCode.REFUND_FAILED, Map.of("bookingId", bookingId));
        }
        bookingTransaction.confirmedToCancelled(userId, bookingId).ifPresent(gate::release);
        return new CancelResult(bookingId, BookingStatus.CANCELLED);
    }
}
