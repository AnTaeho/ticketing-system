package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingStatus;
import java.time.LocalDateTime;
import java.util.List;

// notification은 발권 알림이 아직 없으면 null. cancellable은 확정 상태이고 회차가 DB 시각 기준 시작 전일 때만 true
public record BookingView(
        long bookingId,
        String bookingNumber,
        BookingStatus status,
        LocalDateTime createdAt,
        LocalDateTime cancelledAt,
        int totalAmount,
        String showTitle,
        String venueName,
        LocalDateTime startsAt,
        List<BookingSeatView> seats,
        String notification,
        RefundStatus refund,
        boolean cancellable
) {
}
