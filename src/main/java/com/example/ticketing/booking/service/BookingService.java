package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.dto.BookingCancellation;
import com.example.ticketing.booking.dto.CancelResult;
import com.example.ticketing.hold.service.SeatGateReflector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 게이트 키는 취소 트랜잭션이 커밋하고 돌아온 뒤에 지운다
@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingTransaction bookingTransaction;
    private final SeatGateReflector gate;

    public CancelResult cancel(long userId, long bookingId) {
        BookingCancellation cancellation = bookingTransaction.confirmedToCancelled(userId, bookingId);
        cancellation.releasedGate().ifPresent(gate::releaseSeatsOnly);
        return new CancelResult(bookingId, BookingStatus.CANCELLED, cancellation.refund());
    }
}
