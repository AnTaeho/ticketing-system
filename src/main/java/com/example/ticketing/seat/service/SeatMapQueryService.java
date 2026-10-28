package com.example.ticketing.seat.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.SchedulerLease;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

// 로컬 캐시 → Redis 스냅샷 → (없으면) 리스를 잡은 한 요청만 DB 빌드. Redis 장애 때는 인스턴스 안에서 회차당 초당 한 번만 DB를 읽는다
@Slf4j
@Service
public class SeatMapQueryService {

    static final int MAX_POLLS = 10;
    static final long POLL_INTERVAL_MS = 50;

    private final SeatCatalog seatCatalog;
    private final SeatMapBuilder builder;
    private final SeatMapRedisStore store;
    private final String instanceId;
    private final long localCacheNanos;
    private final Duration leaseTtl;
    private final Duration snapshotTtl;
    private final Counter missRebuilds;
    private final Counter fallbackRebuilds;

    private final ConcurrentMap<Long, Cached> localCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CompletableFuture<String>> fallbackInFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> lastFallbackAttemptNanos = new ConcurrentHashMap<>();
    private final AtomicLong leaseSeq = new AtomicLong();

    public SeatMapQueryService(SeatCatalog seatCatalog, SeatMapBuilder builder, SeatMapRedisStore store,
                               SchedulerLease schedulerLease, TicketingProperties properties,
                               MeterRegistry meterRegistry) {
        this.seatCatalog = seatCatalog;
        this.builder = builder;
        this.store = store;
        this.instanceId = schedulerLease.instanceId();
        this.localCacheNanos = Duration.ofMillis(properties.seatmap().localCacheMs()).toNanos();
        this.leaseTtl = properties.seatmap().leaseTtl();
        this.snapshotTtl = properties.seatmap().snapshotTtl();
        this.missRebuilds = SeatMapBuilder.rebuildCounter(meterRegistry, "miss");
        this.fallbackRebuilds = SeatMapBuilder.rebuildCounter(meterRegistry, "fallback");
    }

    public String get(long performanceId) {
        seatCatalog.performance(performanceId);
        Cached local = localCache.get(performanceId);
        if (local != null && local.isFresh(localCacheNanos)) {
            return local.json();
        }
        String json;
        try {
            json = store.get(performanceId);
        } catch (DataAccessException | RedisException e) {
            return onRedisFailure(performanceId, e);
        }
        if (isEmpty(json)) {
            return rebuildOrWait(performanceId);
        }
        return cache(performanceId, json);
    }

    private String rebuildOrWait(long performanceId) {
        // 요청마다 소유자 값을 달리해 같은 인스턴스의 동시 요청도 리스를 하나만 잡게 한다
        String owner = instanceId + "#" + leaseSeq.incrementAndGet();
        boolean leased;
        try {
            leased = store.tryLease(performanceId, owner, leaseTtl);
        } catch (DataAccessException | RedisException e) {
            return onRedisFailure(performanceId, e);
        }
        if (leased) {
            return buildAndPut(performanceId, owner);
        }
        for (int i = 0; i < MAX_POLLS; i++) {
            sleepPollInterval(performanceId);
            String json;
            try {
                json = store.get(performanceId);
            } catch (DataAccessException | RedisException e) {
                return onRedisFailure(performanceId, e);
            }
            if (!isEmpty(json)) {
                return cache(performanceId, json);
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
            } catch (DataAccessException | RedisException e) {
                return onRedisFailure(performanceId, e);
            }
            if (!isEmpty(saved)) {
                return cache(performanceId, saved);
            }
            String json = buildOrUnavailable(performanceId, missRebuilds);
            try {
                store.put(performanceId, json, snapshotTtl);
            } catch (DataAccessException | RedisException e) {
                log.debug("[booking seatmap] 회차 {} 스냅샷 저장 실패: {}", performanceId, e.toString());
            }
            return cache(performanceId, json);
        } finally {
            try {
                store.releaseLease(performanceId, owner);
            } catch (DataAccessException | RedisException e) {
                log.debug("[booking seatmap] 회차 {} 리스 해제 실패, TTL로 풀린다: {}", performanceId, e.toString());
            }
        }
    }

    // 로컬 캐시가 오래됐으면 회차별로 한 요청만 DB를 읽고 나머지는 그 결과를 기다린다. 빌드가 실패하면 오래된 캐시를 쓴다
    private String onRedisFailure(long performanceId, RuntimeException cause) {
        log.debug("[booking seatmap] 회차 {} Redis 실패, 로컬 캐시·DB로 넘어간다: {}", performanceId, cause.toString());
        Cached stale = localCache.get(performanceId);
        if (stale != null && stale.isFresh(localCacheNanos)) {
            return stale.json();
        }
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> running = fallbackInFlight.putIfAbsent(performanceId, mine);
        if (running != null) {
            try {
                return await(running);
            } catch (RuntimeException e) {
                return staleOrThrow(stale, e);
            }
        }
        try {
            String json = fallbackBuild(performanceId);
            mine.complete(json);
            return json;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            return staleOrThrow(stale, e);
        } finally {
            fallbackInFlight.remove(performanceId, mine);
        }
    }

    // 실패한 빌드도 한 번으로 센다. 그래서 DB가 느리거나 죽어도 회차당 local-cache-ms에 한 번만 읽는다
    private String fallbackBuild(long performanceId) {
        // 앞선 빌드가 막 끝나 자리를 비웠을 수 있다. 그 결과가 로컬 캐시에 있으면 다시 읽지 않는다
        Cached latest = localCache.get(performanceId);
        if (latest != null && latest.isFresh(localCacheNanos)) {
            return latest.json();
        }
        long now = System.nanoTime();
        Long lastAttempt = lastFallbackAttemptNanos.get(performanceId);
        if (lastAttempt != null && now - lastAttempt < localCacheNanos) {
            throw unavailable(performanceId);
        }
        lastFallbackAttemptNanos.put(performanceId, now);
        return cache(performanceId, buildOrUnavailable(performanceId, fallbackRebuilds));
    }

    private static String staleOrThrow(Cached stale, RuntimeException e) {
        if (stale == null) {
            throw e;
        }
        return stale.json();
    }

    private String buildOrUnavailable(long performanceId, Counter counter) {
        var built = builder.build(performanceId);
        counter.increment();
        return built.orElseThrow(() -> unavailable(performanceId));
    }

    private String cache(long performanceId, String json) {
        localCache.put(performanceId, new Cached(json, System.nanoTime()));
        return json;
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

    private static ApiException unavailable(long performanceId) {
        return new ApiException(ErrorCode.SEATMAP_UNAVAILABLE, Map.of("performanceId", performanceId));
    }

    private record Cached(String json, long readAtNanos) {

        boolean isFresh(long ttlNanos) {
            return System.nanoTime() - readAtNanos < ttlNanos;
        }
    }
}
