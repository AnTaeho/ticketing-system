package com.example.ticketing.seat.service;

import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.seat.service.SeatMapLocalCache.Entry;
import io.micrometer.core.instrument.Counter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

// Redis 장애 때 좌석맵을 DB에서 만든다. 인스턴스 안에서 회차당 한 요청만, local-cache 간격에 한 번만 읽는다
@Slf4j
final class SeatMapFallback {

    private final SeatMapBuilder builder;
    private final SeatMapLocalCache localCache;
    private final LongSupplier nanoClock;
    private final long attemptIntervalNanos;
    private final Counter rebuilds;

    private final ConcurrentMap<Long, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> lastAttemptNanos = new ConcurrentHashMap<>();

    SeatMapFallback(SeatMapBuilder builder, SeatMapLocalCache localCache, LongSupplier nanoClock,
                    long attemptIntervalNanos, Counter rebuilds) {
        this.builder = builder;
        this.localCache = localCache;
        this.nanoClock = nanoClock;
        this.attemptIntervalNanos = attemptIntervalNanos;
        this.rebuilds = rebuilds;
    }

    // 로컬 캐시가 오래됐으면 회차별로 한 요청만 DB를 읽고 나머지는 그 결과를 기다린다. 빌드가 실패하면 오래된 캐시를 쓴다
    String read(long performanceId, RedisUnavailableException cause) {
        log.debug("[seatmap] Redis 실패, 로컬 캐시·DB로 넘어간다 performanceId={} cause={}", performanceId,
                RedisFailures.describe(cause));
        Entry stale = localCache.get(performanceId);
        if (localCache.isFresh(stale)) {
            return stale.json();
        }
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> running = inFlight.putIfAbsent(performanceId, mine);
        if (running != null) {
            try {
                return await(running);
            } catch (RuntimeException e) {
                return staleOrThrow(stale, e);
            }
        }
        try {
            String json = build(performanceId);
            mine.complete(json);
            return json;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            return staleOrThrow(stale, e);
        } finally {
            inFlight.remove(performanceId, mine);
        }
    }

    // 실패한 빌드도 한 번으로 센다. 그래서 DB가 느리거나 죽어도 회차당 간격마다 한 번만 읽는다
    private String build(long performanceId) {
        // 앞선 빌드가 막 끝나 자리를 비웠을 수 있다. 그 결과가 로컬 캐시에 있으면 다시 읽지 않는다
        Entry latest = localCache.get(performanceId);
        if (localCache.isFresh(latest)) {
            return latest.json();
        }
        long now = nanoClock.getAsLong();
        Long lastAttempt = lastAttemptNanos.get(performanceId);
        if (lastAttempt != null && now - lastAttempt < attemptIntervalNanos) {
            throw SeatMapQueryService.unavailable(performanceId);
        }
        lastAttemptNanos.put(performanceId, now);
        var built = builder.build(performanceId);
        rebuilds.increment();
        return localCache.put(performanceId, built.orElseThrow(() -> SeatMapQueryService.unavailable(performanceId)));
    }

    private static String staleOrThrow(Entry stale, RuntimeException e) {
        if (stale == null) {
            throw e;
        }
        return stale.json();
    }

    private static String await(CompletableFuture<String> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
