package com.example.ticketing.hold.dto;

import com.example.ticketing.seat.domain.SeatGrade;
import com.example.ticketing.seat.domain.SeatInfo;

public record HoldSeatResult(long id, String section, int rowNo, int seatNo, SeatGrade grade, int price) {

    public static HoldSeatResult from(SeatInfo seat) {
        return new HoldSeatResult(seat.id(), seat.section(), seat.rowNo(), seat.seatNo(), seat.grade(), seat.price());
    }
}
