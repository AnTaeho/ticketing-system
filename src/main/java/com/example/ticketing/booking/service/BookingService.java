package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.dto.BookingCancellation;
import com.example.ticketing.booking.dto.BookingResult;
import com.example.ticketing.booking.dto.CancelResult;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.service.SeatGateReflector;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 게이트 키는 취소 트랜잭션이 커밋하고 돌아온 뒤에 지운다
@Service
public class BookingService {

    private final BookingTransaction bookingTransaction;
    private final BookingReader bookingReader;
    private final SeatGateReflector gate;

    public BookingService(BookingTransaction bookingTransaction, BookingReader bookingReader, SeatGateReflector gate) {
        this.bookingTransaction = bookingTransaction;
        this.bookingReader = bookingReader;
        this.gate = gate;
    }

    public CancelResult cancel(long userId, long bookingId) {
        BookingCancellation cancellation = bookingTransaction.cancel(userId, bookingId);
        cancellation.releasedGate().ifPresent(gate::releaseSeatsOnly);
        return new CancelResult(cancellation.bookingId(), BookingStatus.CANCELLED, cancellation.refund());
    }

    public List<BookingResult> list(long userId) {
        return bookingReader.findByUser(userId);
    }

    public BookingResult detail(long userId, long bookingId) {
        return bookingReader.findOne(userId, bookingId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND,
                        Map.of("bookingId", bookingId)));
    }
}
