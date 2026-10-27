package com.example.ticketing.booking.queue;

import com.example.ticketing.booking.hold.gate.BookingKeys;

// 한 회차의 대기열 키는 같은 해시 태그를 써서 Lua가 함께 만질 수 있게 한다
public final class QueueKeys {

    private QueueKeys() {
    }

    public static String waiting(long performanceId) {
        return "bq:" + BookingKeys.tag(performanceId) + ":waiting";
    }

    public static String processing(long performanceId) {
        return "bq:" + BookingKeys.tag(performanceId) + ":processing";
    }

    public static String user(long performanceId, long userId) {
        return "bq:" + BookingKeys.tag(performanceId) + ":user:" + userId;
    }

    public static String rate(long performanceId) {
        return "bq:" + BookingKeys.tag(performanceId) + ":rate";
    }
}
