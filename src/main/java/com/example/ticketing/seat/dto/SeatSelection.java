package com.example.ticketing.seat.dto;

import com.example.ticketing.seat.domain.SeatInfo;
import java.util.List;

// 검증을 마친 좌석 선택. seatIds·seats는 좌석 ID 오름차순이다
public record SeatSelection(long performanceId, List<Long> seatIds, List<SeatInfo> seats, int totalAmount) {
}
