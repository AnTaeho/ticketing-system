package com.example.ticketing.booking.dto;

import java.time.LocalDateTime;

// 결제 확정 트랜잭션이 넘긴다. seatCount는 사본으로 옮길 SOLD 좌석 수, dbNow는 예매번호 날짜다
public record IssueBookingCommand(long userId, long performanceId, long holdId, long paymentId, int totalAmount,
                                  int seatCount, LocalDateTime dbNow) {
}
