package com.example.ticketing.hold.dto;

import com.example.ticketing.hold.domain.GateHold;

// 사용자 취소가 커밋된 hold. gate.seatIds는 풀기 전에 이 hold가 쥐고 있던 좌석이다
public record HoldRelease(long holdId, GateHold gate) {
}
