package com.example.ticketing.seat.domain;

// 좌석맵 한 칸을 만드는 좌석 하나
public record SeatMapSeat(long id, String section, int rowNo, int seatNo, SeatGrade grade, int price, SeatStatus status) {
}
