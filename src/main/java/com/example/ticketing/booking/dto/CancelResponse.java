package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingStatus;

public record CancelResponse(long bookingId, BookingStatus status, RefundStatus refund) {

    public static CancelResponse from(CancelResult result) {
        return new CancelResponse(result.bookingId(), result.status(), result.refund());
    }
}
