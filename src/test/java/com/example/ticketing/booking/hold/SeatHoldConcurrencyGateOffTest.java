package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import org.springframework.test.context.TestPropertySource;

// 같은 동시성 테스트를 Redis 게이트 없이 돌린다. DB 판정만으로도 성공 1이고 Redis 키가 생기지 않아야 한다
@BookingIntegrationTest
@TestPropertySource(properties = "booking.gate.enabled=false")
class SeatHoldConcurrencyGateOffTest extends SeatHoldConcurrencyTest {
}
