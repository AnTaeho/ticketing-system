package com.example.ticketing.seat.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

// 인스턴스 안의 회차별 좌석맵. Redis 경로와 장애 폴백이 함께 쓴다
final class SeatMapLocalCache {

    private final ConcurrentMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;
    private final long freshNanos;

    SeatMapLocalCache(LongSupplier nanoClock, long freshNanos) {
        this.nanoClock = nanoClock;
        this.freshNanos = freshNanos;
    }

    // 없으면 null
    Entry get(long performanceId) {
        return entries.get(performanceId);
    }

    boolean isFresh(Entry entry) {
        return entry != null && nanoClock.getAsLong() - entry.readAtNanos() < freshNanos;
    }

    String put(long performanceId, String json) {
        entries.put(performanceId, new Entry(json, nanoClock.getAsLong()));
        return json;
    }

    record Entry(String json, long readAtNanos) {
    }
}
