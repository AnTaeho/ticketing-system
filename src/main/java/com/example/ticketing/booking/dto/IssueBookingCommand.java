package com.example.ticketing.booking.dto;

import com.example.ticketing.seat.domain.SeatInfo;
import java.time.LocalDateTime;
import java.util.List;

// 결제 확정 트랜잭션이 넘긴다. seats는 사본으로 남길 SOLD 좌석, dbNow는 예매번호 날짜다
public record IssueBookingCommand(long userId, long performanceId, long holdId, long paymentId, int totalAmount,
                                  List<SeatInfo> seats, LocalDateTime dbNow) {
}
