package com.example.ticketing.seat.domain;

// hold가 아직 HELD로 쥐고 있는 좌석 하나
public record HeldSeat(long holdId, long seatId) {
}
