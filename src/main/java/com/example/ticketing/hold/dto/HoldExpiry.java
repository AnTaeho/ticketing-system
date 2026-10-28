package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.GateHold;

// 만료가 커밋된 hold. gate.seatIds는 이 hold가 아직 쥐고 있던 좌석만이고 남이 가져간 좌석은 빠진다
public record HoldExpiry(long holdId, GateHold gate) {
}
