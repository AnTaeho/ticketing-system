package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingStatus;

public record CancelResult(long bookingId, BookingStatus status, RefundStatus refund) {
}
