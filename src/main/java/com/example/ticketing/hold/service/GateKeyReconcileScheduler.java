package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.OrphanKind;
import com.example.ticketing.common.metrics.Metrics.SkipReason;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.common.redis.SchedulerLease;
import com.example.ticketing.hold.domain.GateKeyEntry;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Redis 반영 실패로 DB와 어긋난 게이트 키를 지운다. 키가 남으면 DB에서 빈 좌석도 게이트가 막는다
@Slf4j
@Component
@ConditionalOnProperty(name = "ticketing.gate.reconcile-enabled", havingValue = "true", matchIfMissing = true)
public class GateKeyReconcileScheduler {

    static final String LEASE = "gate-reconcile";
    static final Duration FRESH_WINDOW = Duration.ofSeconds(10);
    // IN 목록 하나에 넣는 토큰 수
    private static final int IN_CHUNK = 500;

    private final SchedulerLease lease;
    private final SeatGateRedisStore gateStore;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatCatalog seatCatalog;
    private final ThrottledWarn redisWarn;
    private final Duration leaseTtl;
    private final long holdTtlMs;
    private final Counter seatRemoved;
    private final Counter userHoldRemoved;
    private final Counter skippedFresh;

    public GateKeyReconcileScheduler(SchedulerLease lease, SeatGateRedisStore gateStore,
                                     SeatHoldRepository seatHoldRepository, SeatCatalog seatCatalog,
                                     LongSupplier nanoClock, TicketingProperties properties,
                                     MeterRegistry meterRegistry) {
        this.lease = lease;
        this.gateStore = gateStore;
        this.seatHoldRepository = seatHoldRepository;
        this.seatCatalog = seatCatalog;
        this.redisWarn = new ThrottledWarn(log, nanoClock);
        this.leaseTtl = Duration.ofMillis(properties.gate().reconcileScanMs() * 2);
        this.holdTtlMs = properties.hold().ttl().toMillis();
        this.seatRemoved = meterRegistry.counter(Metrics.GATE_ORPHAN_REMOVED, Tag.KIND, OrphanKind.SEAT);
        this.userHoldRemoved = meterRegistry.counter(Metrics.GATE_ORPHAN_REMOVED, Tag.KIND, OrphanKind.USER_HOLD);
        this.skippedFresh = meterRegistry.counter(Metrics.GATE_ORPHAN_SKIPPED, Tag.REASON, SkipReason.FRESH);
    }

    @Scheduled(fixedDelayString = "${ticketing.gate.reconcile-scan-ms}")
    public void run() {
        lease.runIfLeader(LEASE, leaseTtl, this::reconcileAll);
    }

    void reconcileAll() {
        for (long performanceId : seatCatalog.openPerformanceIds()) {
            try {
                reconcileOne(performanceId);
            } catch (RedisUnavailableException e) {
                redisWarn.warn("[gate-reconcile] Redis 오류로 이번 주기를 건너뛴다 performanceId={} cause={}", performanceId,
                        RedisFailures.describe(e));
                return;
            } catch (RuntimeException e) {
                // DB 실패 같은 회차 하나의 실패는 다음 회차로 넘어간다
                log.warn("[gate-reconcile] 회차 대사 실패, 다음 회차로 넘어간다 performanceId={}", performanceId, e);
            }
        }
    }

    void reconcileOne(long performanceId) {
        List<GateKeyEntry> seatKeys = readKeys(RedisKeys.seatPattern(performanceId));
        long seats = release(orphanSeatKeys(performanceId, seatKeys));
        List<GateKeyEntry> userHoldKeys = readKeys(RedisKeys.userHoldPattern(performanceId));
        long userHolds = release(orphanUserHoldKeys(performanceId, userHoldKeys));

        seatRemoved.increment(seats);
        userHoldRemoved.increment(userHolds);
        if (seats + userHolds > 0) {
            log.info("[gate-reconcile] 고아 게이트 키를 지웠다 performanceId={} seats={} userHolds={}", performanceId, seats,
                    userHolds);
        }
    }

    // 막 생긴 선점 키는 DB 커밋 전일 수 있어 건너뛴다. 결제 중·확정 키는 TTL이 더 길어 검사 대상이다
    private List<GateKeyEntry> readKeys(String pattern) {
        List<GateKeyEntry> keys = new ArrayList<>();
        for (GateKeyEntry key : gateStore.scan(pattern)) {
            if (isFresh(key.pttl())) {
                skippedFresh.increment();
            } else {
                keys.add(key);
            }
        }
        return keys;
    }

    private boolean isFresh(Long pttl) {
        return pttl != null && pttl > holdTtlMs - FRESH_WINDOW.toMillis() && pttl <= holdTtlMs;
    }

    private List<GateKeyEntry> orphanSeatKeys(long performanceId, List<GateKeyEntry> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        // 좌석 행은 hold 하나만 가리키므로 좌석 키마다 살아 있는 토큰은 하나다
        Map<String, String> liveTokenByKey = new HashMap<>();
        inChunks(tokens(keys), chunk -> seatHoldRepository.findLiveGateSeats(performanceId, chunk))
                .forEach(seat -> liveTokenByKey.put(RedisKeys.seat(performanceId, seat.seatId()), seat.holdToken()));
        return keys.stream().filter(key -> !key.holdToken().equals(liveTokenByKey.get(key.name()))).toList();
    }

    private List<GateKeyEntry> orphanUserHoldKeys(long performanceId, List<GateKeyEntry> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Set<String> live = new HashSet<>(
                inChunks(tokens(keys), chunk -> seatHoldRepository.findLiveUserHoldTokens(performanceId, chunk)));
        return keys.stream().filter(key -> !live.contains(key.holdToken())).toList();
    }

    // 읽은 뒤 값이 바뀐 키(새 선점이 같은 좌석을 잡음)는 delete_if_equals.lua가 값을 비교해 남긴다
    private long release(List<GateKeyEntry> orphans) {
        Map<String, List<String>> keysByToken = new LinkedHashMap<>();
        orphans.forEach(key -> keysByToken.computeIfAbsent(key.holdToken(), t -> new ArrayList<>()).add(key.name()));
        long removed = 0;
        for (Map.Entry<String, List<String>> entry : keysByToken.entrySet()) {
            removed += gateStore.releaseIfOwned(entry.getKey(), entry.getValue());
        }
        return removed;
    }

    private static <T> List<T> inChunks(List<String> tokens, Function<List<String>, List<T>> query) {
        List<T> results = new ArrayList<>();
        for (int from = 0; from < tokens.size(); from += IN_CHUNK) {
            results.addAll(query.apply(tokens.subList(from, Math.min(from + IN_CHUNK, tokens.size()))));
        }
        return results;
    }

    private static List<String> tokens(List<GateKeyEntry> keys) {
        return keys.stream().map(GateKeyEntry::holdToken).distinct().toList();
    }
}
