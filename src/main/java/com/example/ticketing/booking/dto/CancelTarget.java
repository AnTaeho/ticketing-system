package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingStatus;

// 취소 전 확인용. notStarted는 회차가 DB 시각 기준 시작 전인지다
public record CancelTarget(long bookingId, BookingStatus status, long paymentId, boolean notStarted) {
}
