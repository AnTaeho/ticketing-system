package com.example.ticketing.booking.domain;

import com.example.ticketing.seat.domain.SeatGrade;

public record BookingSeatRow(long bookingId, long seatId, String section, int rowNo, int seatNo, SeatGrade grade,
                             int price) {
}
