package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.GateHold;

// 만료가 커밋된 hold. gate.seatIds는 이 hold가 쥐고 있던 좌석이다
public record HoldExpiry(long holdId, GateHold gate) {
}
