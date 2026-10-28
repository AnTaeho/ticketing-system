package com.example.ticketing.booking.domain;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// 예: B20261028-000042. 날짜는 확정 때의 DB 시각이고 뒤는 hold ID다
public final class BookingNumber {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private BookingNumber() {
    }

    public static String of(LocalDateTime dbNow, long holdId) {
        return "B" + dbNow.format(DATE) + "-" + String.format("%06d", holdId);
    }
}
