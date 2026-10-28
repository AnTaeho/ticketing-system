package com.example.ticketing.common.jdbc;

import java.time.Duration;

// SQL의 INTERVAL :x MICROSECOND에 바인드할 값. 기간은 Duration으로 받고 DB 계산은 마이크로초로 한다
public final class SqlIntervals {

    private SqlIntervals() {
    }

    public static long micros(Duration duration) {
        return duration.toNanos() / 1_000;
    }
}
