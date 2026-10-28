package com.example.ticketing.booking.dto;

import com.example.ticketing.seat.domain.SeatGrade;

public record BookingSeatResponse(long seatId, String section, int rowNo, int seatNo, SeatGrade grade, int price) {

    public static BookingSeatResponse from(BookingSeatResult result) {
        return new BookingSeatResponse(result.seatId(), result.section(), result.rowNo(), result.seatNo(),
                result.grade(), result.price());
    }
}
