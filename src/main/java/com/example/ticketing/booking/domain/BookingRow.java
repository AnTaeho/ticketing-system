package com.example.ticketing.booking.domain;

import java.time.LocalDateTime;

// 예매 조회 한 행. notStarted는 DB 시각 기준 회차 시작 전인지, paymentRefunded는 결제가 환불까지 끝났는지다
public record BookingRow(
        long id,
        String bookingNumber,
        BookingStatus status,
        LocalDateTime createdAt,
        LocalDateTime cancelledAt,
        int totalAmount,
        String title,
        String venueName,
        LocalDateTime startsAt,
        boolean notStarted,
        boolean paymentRefunded,
        String notification
) {
}
