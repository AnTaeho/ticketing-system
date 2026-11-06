package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.Booking;
import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.show.dto.PerformanceSummary;
import java.time.LocalDateTime;
import java.util.List;

// cancellable은 확정 상태이고 회차가 DB 시각 기준 시작 전일 때만 true
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
        boolean cancellable
) {

    public static BookingResult from(Booking booking, PerformanceSummary performance, List<BookingSeatResult> seats,
                                     LocalDateTime dbNow) {
        return new BookingResult(booking.getId(), booking.getBookingNumber(), booking.getStatus(),
                booking.getCreatedAt(), booking.getCancelledAt(), booking.getTotalAmount(), performance.showTitle(),
                performance.venueName(), performance.startsAt(), List.copyOf(seats),
                booking.getStatus() == BookingStatus.CONFIRMED && !performance.hasStartedAt(dbNow));
    }
}
