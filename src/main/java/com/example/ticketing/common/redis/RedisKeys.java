package com.example.ticketing.common.redis;

// 해시 태그 {p:N}로 한 회차의 키를 같은 슬롯에 둔다. Lua가 여러 키를 함께 만지므로 클러스터에서도 필요하다
public final class RedisKeys {

    private RedisKeys() {
    }

    private static String tag(long performanceId) {
        return "{p:" + performanceId + "}";
    }

    public static String seat(long performanceId, long seatId) {
        return "seat:" + tag(performanceId) + ":" + seatId;
    }

    // 키의 마지막 구간이 좌석 ID다
    public static long seatIdOf(String key) {
        return Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
    }
}
