package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.Booking;
import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.repository.BookingRepository;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.domain.Seat;
import com.example.ticketing.seat.repository.SeatRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 락 순서: booking → seat(id 오름차순). 확정된 hold를 바꾸는 경로가 없어 hold 행은 잠그지 않는다. 이 안에서는 Redis·PG를 부르지 않는다
@Component
@Transactional(isolation = Isolation.READ_COMMITTED)
@RequiredArgsConstructor
public class BookingTransaction {

    private final BookingRepository bookingRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final DbClock dbClock;

    // PG 환불이 끝난 뒤 부르므로 회차 시작은 다시 보지 않는다. booking 락이 같은 예매의 취소를 줄 세우고,
    // 이미 취소된 예매는 아무것도 바꾸지 않아 빈 값을 돌려준다. 값이 있으면 커밋 뒤 지울 좌석 게이트 키다
    public Optional<GateHold> confirmedToCancelled(long userId, long bookingId) {
        Booking booking = bookingRepository.lockByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, Map.of("bookingId", bookingId)));
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return Optional.empty();
        }

        booking.confirmedToCancelled(dbClock.now());
        SeatHold hold = seatHoldRepository.findById(booking.getHoldId())
                .orElseThrow(() -> new IllegalStateException("예매 " + bookingId + "의 hold가 없다"));
        // 게이트 키를 지울 좌석 ID는 hold_id를 비우기 전에 읽어 둔다
        List<Seat> seats = seatRepository.lockSoldSeats(hold.getId());
        List<Long> seatIds = seats.stream().map(Seat::getId).toList();
        seats.forEach(Seat::soldToAvailable);
        return Optional.of(new GateHold(booking.getPerformanceId(), hold.getHoldToken(), seatIds));
    }
}
