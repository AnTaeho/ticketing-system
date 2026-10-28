package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.HoldStatus;
import java.time.LocalDateTime;
import java.util.List;

// seats는 지금 이 선점을 가리키는 좌석이다. 풀리거나 남이 가져간 좌석은 빠진다
public record HoldDetail(
        long holdId,
        long performanceId,
        HoldStatus status,
        LocalDateTime expiresAt,
        LocalDateTime payDeadline,
        LocalDateTime serverNow,
        List<HoldSeatView> seats,
        int totalAmount,
        Long paymentId
) {
}
