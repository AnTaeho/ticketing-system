package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.seat.domain.SeatInfo;
import java.time.LocalDateTime;
import java.util.List;

// seats는 지금 이 선점을 가리키는 좌석이다. 풀리거나 남이 가져간 좌석은 빠진다
public record HoldDetailResult(
        long holdId,
        long performanceId,
        HoldStatus status,
        LocalDateTime expiresAt,
        LocalDateTime dbNow, // 응답을 만든 DB 시각. 화면은 expiresAt과의 차로 남은 시간을 센다
        List<SeatInfo> seats,
        int totalAmount,
        Long paymentId
) {

    public static HoldDetailResult from(SeatHold hold, List<SeatInfo> seats, Long paymentId, LocalDateTime dbNow) {
        return new HoldDetailResult(hold.getId(), hold.getPerformanceId(), hold.getStatus(), hold.getExpiresAt(),
                dbNow, List.copyOf(seats), hold.getTotalAmount(), paymentId);
    }
}
