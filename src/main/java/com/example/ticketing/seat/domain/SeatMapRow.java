package com.example.ticketing.seat.domain;

// 좌석맵 한 칸의 원본 행. grade는 DB 문자열 그대로 내보낸다
public record SeatMapRow(long id, String section, int rowNo, int seatNo, String grade, int price, SeatStatus status) {
}
