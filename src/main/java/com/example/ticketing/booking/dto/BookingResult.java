package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingStatus;
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

    // BookingRepository의 JPQL이 부른다. 좌석은 withSeats로 붙인다
    public BookingResult(long bookingId, String bookingNumber, BookingStatus status, LocalDateTime createdAt,
                         LocalDateTime cancelledAt, int totalAmount, String showTitle, String venueName,
                         LocalDateTime startsAt, boolean notStarted) {
        this(bookingId, bookingNumber, status, createdAt, cancelledAt, totalAmount, showTitle, venueName, startsAt,
                List.of(), status == BookingStatus.CONFIRMED && notStarted);
    }

    public BookingResult withSeats(List<BookingSeatResult> seats) {
        return new BookingResult(bookingId, bookingNumber, status, createdAt, cancelledAt, totalAmount, showTitle,
                venueName, startsAt, List.copyOf(seats), cancellable);
    }
}
