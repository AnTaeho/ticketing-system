package com.example.ticketing.hold.domain;

// 좌석 행이 이 토큰의 hold를 가리키는 HELD·SOLD 좌석. 이 쌍의 좌석 키만 살아 있는 키다
public record LiveGateSeat(String holdToken, long seatId) {
}
