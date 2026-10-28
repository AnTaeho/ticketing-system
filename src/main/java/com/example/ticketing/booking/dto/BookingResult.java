package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingRow;
import com.example.ticketing.booking.domain.BookingStatus;
import java.time.LocalDateTime;
import java.util.List;

// notification은 발권 알림이 아직 없으면 null. cancellable은 확정 상태이고 회차가 DB 시각 기준 시작 전일 때만 true
public record BookingResult(
        long bookingId,
        String bookingNumber,
        BookingStatus status,
        LocalDateTime createdAt,
        LocalDateTime cancelledAt,
        int totalAmount,
        String showTitle,
        String venueName,
        LocalDateTime startsAt,
        List<BookingSeatResult> seats,
        String notification,
        RefundStatus refund,
        boolean cancellable
) {

    public static BookingResult from(BookingRow row, List<BookingSeatResult> seats) {
        return new BookingResult(row.id(), row.bookingNumber(), row.status(), row.createdAt(), row.cancelledAt(),
                row.totalAmount(), row.title(), row.venueName(), row.startsAt(), List.copyOf(seats),
                row.notification(), RefundStatus.of(row.status(), row.paymentRefunded()),
                row.status() == BookingStatus.CONFIRMED && row.notStarted());
    }
}
