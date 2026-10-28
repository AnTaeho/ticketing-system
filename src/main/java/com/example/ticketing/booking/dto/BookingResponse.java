package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingStatus;
import java.time.LocalDateTime;
import java.util.List;

public record BookingResponse(
        long bookingId,
        String bookingNumber,
        BookingStatus status,
        LocalDateTime createdAt,
        LocalDateTime cancelledAt,
        int totalAmount,
        String showTitle,
        String venueName,
        LocalDateTime startsAt,
        List<BookingSeatResponse> seats,
        String notification,
        RefundStatus refund,
        boolean cancellable
) {

    public static BookingResponse from(BookingResult result) {
        return new BookingResponse(result.bookingId(), result.bookingNumber(), result.status(), result.createdAt(),
                result.cancelledAt(), result.totalAmount(), result.showTitle(), result.venueName(),
                result.startsAt(), result.seats().stream().map(BookingSeatResponse::from).toList(),
                result.notification(), result.refund(), result.cancellable());
    }
}
