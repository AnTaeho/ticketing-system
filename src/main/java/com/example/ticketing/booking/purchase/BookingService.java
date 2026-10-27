package com.example.ticketing.booking.purchase;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.purchase.BookingTransaction.Cancellation;
import com.example.ticketing.booking.purchase.domain.BookingStatus;
import com.example.ticketing.booking.purchase.dto.BookingView;
import com.example.ticketing.booking.purchase.dto.CancelResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 게이트 키는 취소 트랜잭션이 커밋하고 돌아온 뒤에 지운다
@Service
public class BookingService {

    private final BookingTransaction bookingTransaction;
    private final BookingViewReader viewReader;
    private final RedisSeatGate gate;
    private final boolean gateEnabled;

    public BookingService(BookingTransaction bookingTransaction, BookingViewReader viewReader, RedisSeatGate gate,
                          BookingProperties properties) {
        this.bookingTransaction = bookingTransaction;
        this.viewReader = viewReader;
        this.gate = gate;
        this.gateEnabled = properties.gate().enabled();
    }

    // 이번 호출이 취소했을 때만 반영한다. 이미 취소된 예매의 좌석은 그새 남이 잡았을 수 있다
    public CancelResponse cancel(long userId, long bookingId) {
        Cancellation cancellation = bookingTransaction.cancel(userId, bookingId);
        if (gateEnabled && cancellation.applied()) {
            gate.releaseSeatsOnlyQuietly(cancellation.performanceId(), cancellation.seatIds(),
                    cancellation.holdToken());
        }
        return new CancelResponse(cancellation.bookingId(), BookingStatus.CANCELLED, cancellation.refund());
    }

    public List<BookingView> list(long userId) {
        return viewReader.findByUser(userId);
    }

    public BookingView detail(long userId, long bookingId) {
        return viewReader.findOne(userId, bookingId)
                .orElseThrow(() -> new BookingException(BookingErrorCode.BOOKING_NOT_FOUND,
                        Map.of("bookingId", bookingId)));
    }
}
