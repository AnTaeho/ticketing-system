package com.example.ticketing.seat.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics.RebuildSource;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 로컬 캐시 → Redis 스냅샷 → (스냅샷이 없거나 Redis 장애면) 서버 안에서 회차당 한 요청만 DB로 만든다
@Slf4j
@Service
public class SeatMapQueryService {

    private final SeatCatalog seatCatalog;
    private final SeatMapBuilder builder;
    private final SeatMapRedisStore store;
    private final LongSupplier nanoClock;
    private final long localCacheNanos;
    private final Counter missRebuilds;
    private final Counter fallbackRebuilds;
    private final ConcurrentMap<Long, Slot> slots = new ConcurrentHashMap<>();

    public SeatMapQueryService(SeatCatalog seatCatalog, SeatMapBuilder builder, SeatMapRedisStore store,
                               LongSupplier nanoClock, TicketingProperties properties, MeterRegistry meterRegistry) {
        this.seatCatalog = seatCatalog;
        this.builder = builder;
        this.store = store;
        this.nanoClock = nanoClock;
        // Redis 스냅샷이 스케줄러 주기마다 바뀌므로 로컬 캐시도 한 주기만 쓴다
        this.localCacheNanos = Duration.ofMillis(properties.seatmap().snapshotIntervalMs()).toNanos();
        this.missRebuilds = SeatMapBuilder.rebuildCounter(meterRegistry, RebuildSource.MISS);
        this.fallbackRebuilds = SeatMapBuilder.rebuildCounter(meterRegistry, RebuildSource.FALLBACK);
    }

    public String get(long performanceId) {
        seatCatalog.performance(performanceId);
        Slot slot = slots.computeIfAbsent(performanceId, id -> new Slot());
        Cached cached = slot.cached;
        if (isFresh(cached)) {
            return cached.json();
        }
        String json;
        try {
            json = store.get(performanceId);
        } catch (RedisUnavailableException e) {
            log.debug("[seatmap] Redis 실패, DB로 만든다 performanceId={} cause={}", performanceId,
                    RedisFailures.describe(e));
            return buildOnce(performanceId, slot, false);
        }
        if (json == null) {
            return buildOnce(performanceId, slot, true);
        }
        slot.cached = new Cached(json, nanoClock.getAsLong());
        return json;
    }

    // 대기열이 요청마다 DB를 치지 않도록 스냅샷 스케줄러가 쓴 표시만 본다. Redis 장애는 호출자가 정한다
    public boolean isSoldOut(long performanceId) {
        return store.isSoldOut(performanceId);
    }

    // 회차마다 한 요청만 DB를 읽고 나머지는 잠금 뒤에서 그 결과를 쓴다. 빌드가 실패하면 낡은 캐시를 준다
    private String buildOnce(long performanceId, Slot slot, boolean redisUp) {
        synchronized (slot) {
            Cached cached = slot.cached;
            if (isFresh(cached)) {
                return cached.json();
            }
            try {
                String json = build(performanceId, slot, redisUp);
                slot.cached = new Cached(json, nanoClock.getAsLong());
                return json;
            } catch (RuntimeException e) {
                if (cached == null) {
                    throw e;
                }
                return cached.json();
            }
        }
    }

    // 실패한 빌드도 한 번으로 센다. 그래서 DB가 느리거나 죽어도 회차당 캐시 간격마다 한 번만 읽는다
    private String build(long performanceId, Slot slot, boolean redisUp) {
        long now = nanoClock.getAsLong();
        if (slot.lastBuildNanos != null && now - slot.lastBuildNanos < localCacheNanos) {
            throw unavailable(performanceId);
        }
        slot.lastBuildNanos = now;
        Optional<String> built = builder.build(performanceId);
        (redisUp ? missRebuilds : fallbackRebuilds).increment();
        String json = built.orElseThrow(() -> unavailable(performanceId));
        if (redisUp) {
            saveQuietly(performanceId, json);
        }
        return json;
    }

    // 저장이 실패해도 이미 만든 좌석맵은 돌려준다
    private void saveQuietly(long performanceId, String json) {
        try {
            store.put(performanceId, json);
        } catch (RedisUnavailableException e) {
            log.debug("[seatmap] 스냅샷 저장 실패 performanceId={} cause={}", performanceId, RedisFailures.describe(e));
        }
    }

    private boolean isFresh(Cached cached) {
        return cached != null && nanoClock.getAsLong() - cached.atNanos() < localCacheNanos;
    }

    private static ApiException unavailable(long performanceId) {
        return new ApiException(ErrorCode.SEATMAP_UNAVAILABLE, Map.of("performanceId", performanceId));
    }

    // 회차 하나의 로컬 좌석맵. DB 빌드는 이 객체로 잠근다
    private static final class Slot {
        private volatile Cached cached;
        private Long lastBuildNanos; // 잠금 안에서만 읽고 쓴다
    }

    private record Cached(String json, long atNanos) {
    }
}
