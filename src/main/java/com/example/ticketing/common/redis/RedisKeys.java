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

    public static String seatPattern(long performanceId) {
        return "seat:" + tag(performanceId) + ":*";
    }

    public static String userHold(long performanceId, long userId) {
        return "userhold:" + tag(performanceId) + ":" + userId;
    }

    public static String userHoldPattern(long performanceId) {
        return "userhold:" + tag(performanceId) + ":*";
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

    public static String seatMap(long performanceId) {
        return "seatmap:" + tag(performanceId);
    }

    public static String seatMapLease(long performanceId) {
        return "seatmap:lease:" + tag(performanceId);
    }

    // 회차에 묶이지 않은 서버 전체 리스라 해시 태그가 없다
    public static String schedulerLease(String name) {
        return "sched:lease:" + name;
    }
}
