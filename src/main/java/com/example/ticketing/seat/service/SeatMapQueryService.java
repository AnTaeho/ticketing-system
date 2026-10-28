package com.example.ticketing.seat.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics.RebuildSource;
import com.example.ticketing.common.redis.InstanceId;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import com.example.ticketing.seat.service.SeatMapLocalCache.Entry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 로컬 캐시 → Redis 스냅샷 → (없으면) 리스를 잡은 한 요청만 DB 빌드. Redis 장애는 SeatMapFallback이 맡는다
@Slf4j
@Service
public class SeatMapQueryService {

    static final int MAX_POLLS = 10;
    static final long POLL_INTERVAL_MS = 50;

    private final SeatCatalog seatCatalog;
    private final SeatMapBuilder builder;
    private final SeatMapRedisStore store;
    private final String instanceId;
    private final Duration leaseTtl;
    private final Duration snapshotTtl;
    private final Counter missRebuilds;
    private final SeatMapLocalCache localCache;
    private final SeatMapFallback fallback;
    private final AtomicLong leaseSeq = new AtomicLong();

    public SeatMapQueryService(SeatCatalog seatCatalog, SeatMapBuilder builder, SeatMapRedisStore store,
                               InstanceId instanceId, LongSupplier nanoClock, TicketingProperties properties,
                               MeterRegistry meterRegistry) {
        this.seatCatalog = seatCatalog;
        this.builder = builder;
        this.store = store;
        this.instanceId = instanceId.value();
        this.leaseTtl = properties.seatmap().leaseTtl();
        this.snapshotTtl = properties.seatmap().snapshotTtl();
        this.missRebuilds = SeatMapBuilder.rebuildCounter(meterRegistry, RebuildSource.MISS);
        long localCacheNanos = properties.seatmap().localCache().toNanos();
        this.localCache = new SeatMapLocalCache(nanoClock, localCacheNanos);
        this.fallback = new SeatMapFallback(builder, localCache, nanoClock, localCacheNanos,
                SeatMapBuilder.rebuildCounter(meterRegistry, RebuildSource.FALLBACK));
    }

    public String get(long performanceId) {
        seatCatalog.performance(performanceId);
        Entry local = localCache.get(performanceId);
        if (localCache.isFresh(local)) {
            return local.json();
        }
        String json;
        try {
            json = store.get(performanceId);
        } catch (RedisUnavailableException e) {
            return fallback.read(performanceId, e);
        }
        if (isEmpty(json)) {
            return rebuildOrWait(performanceId);
        }
        return localCache.put(performanceId, json);
    }

    // 대기열이 요청마다 DB를 치지 않도록 스냅샷 스케줄러가 쓴 표시만 본다. Redis 장애는 호출자가 정한다
    public boolean isSoldOut(long performanceId) {
        return store.isSoldOut(performanceId);
    }

    private String rebuildOrWait(long performanceId) {
        // 요청마다 소유자 값을 달리해 같은 인스턴스의 동시 요청도 리스를 하나만 잡게 한다
        String owner = instanceId + "#" + leaseSeq.incrementAndGet();
        boolean leased;
        try {
            leased = store.tryLease(performanceId, owner, leaseTtl);
        } catch (RedisUnavailableException e) {
            return fallback.read(performanceId, e);
        }
        if (leased) {
            return buildAndPut(performanceId, owner);
        }
        for (int i = 0; i < MAX_POLLS; i++) {
            sleepPollInterval(performanceId);
            String json;
            try {
                json = store.get(performanceId);
            } catch (RedisUnavailableException e) {
                return fallback.read(performanceId, e);
            }
            if (!isEmpty(json)) {
                return localCache.put(performanceId, json);
            }
        }
        throw unavailable(performanceId);
    }

    // 저장이나 리스 해제가 실패해도 이미 만든 좌석맵은 돌려준다
    private String buildAndPut(long performanceId, String owner) {
        try {
            // 앞선 리스 보유자가 저장하고 막 리스를 푼 뒤일 수 있다. 그 스냅샷이 있으면 다시 만들지 않는다
            String saved;
            try {
                saved = store.get(performanceId);
            } catch (RedisUnavailableException e) {
                return fallback.read(performanceId, e);
            }
            if (!isEmpty(saved)) {
                return localCache.put(performanceId, saved);
            }
            var built = builder.build(performanceId);
            missRebuilds.increment();
            String json = built.orElseThrow(() -> unavailable(performanceId));
            try {
                store.put(performanceId, json, snapshotTtl);
            } catch (RedisUnavailableException e) {
                log.debug("[seatmap] 스냅샷 저장 실패 performanceId={} cause={}", performanceId, RedisFailures.describe(e));
            }
            return localCache.put(performanceId, json);
        } finally {
            try {
                store.releaseLease(performanceId, owner);
            } catch (RedisUnavailableException e) {
                log.debug("[seatmap] 리스 해제 실패, TTL로 풀린다 performanceId={} cause={}", performanceId,
                        RedisFailures.describe(e));
            }
        }
    }

    static ApiException unavailable(long performanceId) {
        return new ApiException(ErrorCode.SEATMAP_UNAVAILABLE, Map.of("performanceId", performanceId));
    }

    private static void sleepPollInterval(long performanceId) {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(performanceId);
        }
    }

    private static boolean isEmpty(String json) {
        return json == null || json.isBlank();
    }
}
