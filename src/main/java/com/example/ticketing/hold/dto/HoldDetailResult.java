package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.seat.domain.Seat;
import java.time.LocalDateTime;
import java.util.List;

// seats는 지금 이 선점을 가리키는 좌석이다. 풀리거나 남이 가져간 좌석은 빠진다
public record HoldDetailResult(
        long holdId,
        long performanceId,
        HoldStatus status,
        LocalDateTime expiresAt,
        LocalDateTime payDeadline,
        LocalDateTime serverNow,
        List<HoldSeatResult> seats,
        int totalAmount,
        Long paymentId
) {

    public static HoldDetailResult from(SeatHold hold, List<Seat> seats, Long paymentId, LocalDateTime serverNow) {
        return new HoldDetailResult(hold.getId(), hold.getPerformanceId(), hold.getStatus(), hold.getExpiresAt(),
                hold.getPayDeadline(), serverNow, seats.stream().map(HoldSeatResult::from).toList(),
                hold.getTotalAmount(), paymentId);
    }
}
