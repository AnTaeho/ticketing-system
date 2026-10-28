package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.dto.BookingView;
import com.example.ticketing.booking.dto.CancelResponse;
import com.example.ticketing.booking.service.BookingTransaction.Cancellation;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.service.SeatGateReflector;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 게이트 키는 취소 트랜잭션이 커밋하고 돌아온 뒤에 지운다
@Service
public class BookingService {

    private final BookingTransaction bookingTransaction;
    private final BookingViewReader viewReader;
    private final SeatGateReflector gate;

    public BookingService(BookingTransaction bookingTransaction, BookingViewReader viewReader, SeatGateReflector gate) {
        this.bookingTransaction = bookingTransaction;
        this.viewReader = viewReader;
        this.gate = gate;
    }

    // 이번 호출이 취소했을 때만 반영한다. 이미 취소된 예매의 좌석은 그새 남이 잡았을 수 있다
    public CancelResponse cancel(long userId, long bookingId) {
        Cancellation cancellation = bookingTransaction.cancel(userId, bookingId);
        if (cancellation.applied()) {
            gate.releaseSeatsOnly(new GateHold(userId, cancellation.performanceId(), cancellation.holdToken(),
                    cancellation.seatIds()));
        }
        return new CancelResponse(cancellation.bookingId(), BookingStatus.CANCELLED, cancellation.refund());
    }

    public List<BookingView> list(long userId) {
        return viewReader.findByUser(userId);
    }

    public BookingView detail(long userId, long bookingId) {
        return viewReader.findOne(userId, bookingId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND,
                        Map.of("bookingId", bookingId)));
    }
}
