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

    public static String userHold(long userId, long performanceId) {
        return "userhold:" + tag(performanceId) + ":" + userId;
    }

    public static String userHoldPattern(long performanceId) {
        return "userhold:" + tag(performanceId) + ":*";
    }

    public static String rehold(long userId, long performanceId, long seatId) {
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

    // 처리열 토큰의 입장 시각(ms). 생존 신호가 최대 세션 길이를 넘겨 늘리지 못하게 한다
    public static String queueAdmitted(long performanceId) {
        return "bq:" + tag(performanceId) + ":admitted";
    }

    // 대기 중에 화면을 떠난 토큰과 줄에서 뺄 시각(ms). 유예 안에 돌아오면 지운다
    public static String queueLeaving(long performanceId) {
        return "bq:" + tag(performanceId) + ":leaving";
    }

    public static String queueUser(long userId, long performanceId) {
        return "bq:" + tag(performanceId) + ":user:" + userId;
    }

    public static String queueRate(long performanceId) {
        return "bq:" + tag(performanceId) + ":rate";
    }

    public static String seatMap(long performanceId) {
        return "seatmap:" + tag(performanceId);
    }

    // 좌석맵 스냅샷과 함께 쓰는 매진 표시("1"이면 모든 좌석이 SOLD)
    public static String seatMapSoldOut(long performanceId) {
        return "seatmap:soldout:" + tag(performanceId);
    }

    public static String seatMapLease(long performanceId) {
        return "seatmap:lease:" + tag(performanceId);
    }

    // 회차에 묶이지 않은 서버 전체 리스라 해시 태그가 없다
    public static String schedulerLease(String name) {
        return "sched:lease:" + name;
    }
}
