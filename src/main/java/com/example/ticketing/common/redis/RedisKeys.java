package com.example.ticketing.common.redis;

// 해시 태그 {p:N}로 한 회차의 키를 같은 슬롯에 둔다. Lua가 여러 키를 함께 만지므로 클러스터에서도 필요하다
public final class RedisKeys {

    private RedisKeys() {
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

    public static String queueWaiting(long performanceId) {
        return "bq:" + tag(performanceId) + ":waiting";
    }

    public static String queueProcessing(long performanceId) {
        return "bq:" + tag(performanceId) + ":processing";
    }

    public static String queueUser(long performanceId, long userId) {
        return "bq:" + tag(performanceId) + ":user:" + userId;
    }

    public static String queueRate(long performanceId) {
        return "bq:" + tag(performanceId) + ":rate";
    }
}
