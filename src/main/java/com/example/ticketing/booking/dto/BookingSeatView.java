package com.example.ticketing.booking.dto;

import com.example.ticketing.seat.domain.SeatGrade;

// 확정 때 남긴 booking_seat 사본. 취소 뒤에도 그대로 보인다
public record BookingSeatView(long seatId, String section, int rowNo, int seatNo, SeatGrade grade, int price) {
}
