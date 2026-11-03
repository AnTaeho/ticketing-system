package com.example.ticketing.booking.dto;

import com.example.ticketing.booking.domain.BookingSeat;
import com.example.ticketing.seat.domain.SeatGrade;

// 확정 때 남긴 booking_seat 사본. 취소 뒤에도 그대로 보인다
public record BookingSeatResult(long seatId, String section, int rowNo, int seatNo, SeatGrade grade, int price) {

    public static BookingSeatResult from(BookingSeat seat) {
        return new BookingSeatResult(seat.getId().getSeatId(), seat.getSection(), seat.getRowNo(), seat.getSeatNo(),
                seat.getGrade(), seat.getPrice());
    }
}
