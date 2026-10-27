package com.example.ticketing.booking.hold.dto;

import com.example.ticketing.booking.seat.SeatCatalog.SeatInfo;
import com.example.ticketing.booking.seat.domain.Seat;
import com.example.ticketing.booking.show.domain.SeatGrade;

public record HoldSeatView(long id, String section, int rowNo, int seatNo, SeatGrade grade, int price) {

    public static HoldSeatView of(SeatInfo seat) {
        return new HoldSeatView(seat.id(), seat.section(), seat.rowNo(), seat.seatNo(), seat.grade(), seat.price());
    }

    public static HoldSeatView of(Seat seat) {
        return new HoldSeatView(seat.getId(), seat.getSection(), seat.getRowNo(), seat.getSeatNo(), seat.getGrade(),
                seat.getPrice());
    }
}
