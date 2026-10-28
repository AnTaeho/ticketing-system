package com.example.ticketing.hold.dto;

import com.example.ticketing.seat.domain.SeatGrade;

public record HoldSeatResponse(long id, String section, int rowNo, int seatNo, SeatGrade grade, int price) {

    public static HoldSeatResponse from(HoldSeatResult seat) {
        return new HoldSeatResponse(seat.id(), seat.section(), seat.rowNo(), seat.seatNo(), seat.grade(),
                seat.price());
    }
}
