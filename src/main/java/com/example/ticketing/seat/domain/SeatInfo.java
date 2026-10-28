package com.example.ticketing.seat.domain;

// 생성 뒤 바뀌지 않는 좌석 위치와 가격
public record SeatInfo(long id, String section, int rowNo, int seatNo, SeatGrade grade, int price) {
}
