package com.example.ticketing.notification.domain;

import java.time.LocalDateTime;

// 발권 알림 문구에 쓰는 예매 한 건
public record IssuedBookingRow(long userId, String bookingNumber, String title, LocalDateTime startsAt) {
}
