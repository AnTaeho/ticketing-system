package com.example.ticketing.booking.hold.gate;

// 해시 태그 {p:N}로 한 회차의 키를 같은 슬롯에 둔다. Lua가 여러 키를 함께 만지므로 클러스터에서도 필요하다
public final class BookingKeys {

    private BookingKeys() {
    }

    public static String tag(long performanceId) {
        return "{p:" + performanceId + "}";
    }

    public static String seat(long performanceId, long seatId) {
        return "seat:" + tag(performanceId) + ":" + seatId;
    }

    public static String userHold(long performanceId, long userId) {
        return "userhold:" + tag(performanceId) + ":" + userId;
    }

    public static String rehold(long performanceId, long userId, long seatId) {
        return "rehold:" + tag(performanceId) + ":" + userId + ":" + seatId;
    }

    // seat·rehold 키 모두 마지막 구간이 좌석 ID다
    public static long seatIdOf(String key) {
        return Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
    }
}
