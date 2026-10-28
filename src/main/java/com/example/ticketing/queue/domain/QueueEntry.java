package com.example.ticketing.queue.domain;

// 대기열 진입 한 건. 시각은 서버 벽시계(ms)이고 Redis 점수와 같은 시계다
public record QueueEntry(
        long userId,
        long performanceId,
        String queueToken,
        long enteredAtMs,
        long processingExpireAtMs,
        int capacity,
        long waitingTtlMs
) {
}
