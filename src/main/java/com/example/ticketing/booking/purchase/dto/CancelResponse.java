package com.example.ticketing.booking.purchase.dto;

import com.example.ticketing.booking.purchase.domain.BookingStatus;

public record CancelResponse(long bookingId, BookingStatus status, RefundStatus refund) {
}
