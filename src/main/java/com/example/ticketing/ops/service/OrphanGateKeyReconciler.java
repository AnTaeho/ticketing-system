package com.example.ticketing.ops.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.OrphanKind;
import com.example.ticketing.common.metrics.Metrics.SkipReason;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisScripts;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.common.redis.SchedulerLease;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.seat.service.SeatCatalog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Redis 반영 실패로 DB와 어긋난 게이트 키를 지운다. 키가 남으면 DB에서 빈 좌석도 게이트가 막는다
@Slf4j
@Component
@ConditionalOnProperty(name = "ticketing.ops.orphan-enabled", havingValue = "true", matchIfMissing = true)
public class OrphanGateKeyReconciler {

    static final String LEASE = "orphan";
    static final Duration FRESH_WINDOW = Duration.ofSeconds(10);

    private static final long SCAN_COUNT = 200;
    private static final int PIPELINE_BATCH = 200;
    private static final int IN_CHUNK = 500;

    // 좌석 행의 hold_id가 이 hold이고 HELD·SOLD인 (토큰, 좌석)만 살아 있는 키다
    private static final String LIVE_SEAT_SQL = """
            SELECT h.hold_token, s.id AS seat_id
              FROM seat_hold h
              JOIN seat s ON s.hold_id = h.id
             WHERE h.performance_id = :pid
               AND h.hold_token IN (:tokens)
               AND s.performance_id = :pid
               AND s.status IN ('HELD', 'SOLD')""";

    private static final String LIVE_USER_HOLD_SQL = """
            SELECT hold_token
              FROM seat_hold
             WHERE performance_id = :pid
               AND hold_token IN (:tokens)
               AND status IN ('HELD', 'PAYING')""";

    private final SchedulerLease lease;
    private final StringRedisTemplate redis;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SeatCatalog seatCatalog;
    private final Duration leaseTtl;
    private final long holdTtlMs;
    private final Counter seatRemoved;
    private final Counter userHoldRemoved;
    private final Counter skippedFresh;

    public OrphanGateKeyReconciler(SchedulerLease lease, TicketingRedis ticketingRedis,
                                   NamedParameterJdbcTemplate jdbcTemplate, SeatCatalog seatCatalog,
                                   TicketingProperties properties, MeterRegistry meterRegistry) {
        this.lease = lease;
        this.redis = ticketingRedis.template();
        this.jdbcTemplate = jdbcTemplate;
        this.seatCatalog = seatCatalog;
        this.leaseTtl = Duration.ofMillis(properties.ops().orphanScanMs() * 2);
        this.holdTtlMs = properties.hold().ttl().toMillis();
        this.seatRemoved = meterRegistry.counter(Metrics.GATE_ORPHAN_REMOVED, Tag.KIND, OrphanKind.SEAT);
        this.userHoldRemoved = meterRegistry.counter(Metrics.GATE_ORPHAN_REMOVED, Tag.KIND, OrphanKind.USER_HOLD);
        this.skippedFresh = meterRegistry.counter(Metrics.GATE_ORPHAN_SKIPPED, Tag.REASON, SkipReason.FRESH);
    }

    @Scheduled(fixedDelayString = "${ticketing.ops.orphan-scan-ms}")
    public void reconcile() {
        lease.runIfLeader(LEASE, leaseTtl, this::reconcileAll);
    }

    void reconcileAll() {
        for (long performanceId : seatCatalog.openPerformanceIds()) {
            try {
                reconcileOne(performanceId);
            } catch (RedisUnavailableException e) {
                log.warn("[booking orphan] Redis 오류로 이번 주기를 건너뛴다(회차 {}): {}",
                        performanceId, RedisFailures.describe(e));
                return;
            } catch (RuntimeException e) {
                // DB 실패 같은 회차 하나의 실패는 다음 회차로 넘어간다
                log.warn("[booking orphan] 회차 {} 대사 실패, 다음 회차로 넘어간다: {}", performanceId, e.toString());
            }
        }
    }

    Removed reconcileOne(long performanceId) {
        List<GateKey> seatKeys = readKeys(RedisKeys.seatPattern(performanceId));
        long seats = release(orphanSeatKeys(performanceId, seatKeys));
        List<GateKey> userHoldKeys = readKeys(RedisKeys.userHoldPattern(performanceId));
        long userHolds = release(orphanUserHoldKeys(performanceId, userHoldKeys));

        seatRemoved.increment(seats);
        userHoldRemoved.increment(userHolds);
        if (seats + userHolds > 0) {
            log.info("[booking orphan] 회차 {} 고아 게이트 키 삭제: 좌석 {}, userhold {}", performanceId, seats, userHolds);
        }
        return new Removed(seats, userHolds);
    }

    // 막 생긴 선점 키는 DB 커밋 전일 수 있어 건너뛴다. 결제 중·확정 키는 TTL이 더 길어 검사 대상이다
    private List<GateKey> readKeys(String pattern) {
        return RedisFailures.translate(() -> {
            Set<String> names = new LinkedHashSet<>();
            try (Cursor<String> cursor = redis.scan(
                    ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build())) {
                cursor.forEachRemaining(names::add);
            }
            List<String> all = List.copyOf(names);
            List<GateKey> keys = new ArrayList<>();
            for (int from = 0; from < all.size(); from += PIPELINE_BATCH) {
                List<String> batch = all.subList(from, Math.min(from + PIPELINE_BATCH, all.size()));
                List<Object> replies = redis.executePipelined((RedisCallback<Object>) connection -> {
                    for (String name : batch) {
                        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
                        connection.stringCommands().get(raw);
                        connection.keyCommands().pTtl(raw);
                    }
                    return null;
                });
                for (int i = 0; i < batch.size(); i++) {
                    String token = (String) replies.get(2 * i);
                    Long pttl = (Long) replies.get(2 * i + 1);
                    if (token == null) {
                        continue;
                    }
                    if (isFresh(pttl)) {
                        skippedFresh.increment();
                        continue;
                    }
                    keys.add(new GateKey(batch.get(i), token));
                }
            }
            return keys;
        });
    }

    private boolean isFresh(Long pttl) {
        return pttl != null && pttl > holdTtlMs - FRESH_WINDOW.toMillis() && pttl <= holdTtlMs;
    }

    private List<GateKey> orphanSeatKeys(long performanceId, List<GateKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Set<String> live = new HashSet<>();
        forEachChunk(tokens(keys), chunk -> jdbcTemplate.query(LIVE_SEAT_SQL, params(performanceId, chunk), rs -> {
            live.add(RedisKeys.seat(performanceId, rs.getLong("seat_id")) + " " + rs.getString("hold_token"));
        }));
        return keys.stream().filter(key -> !live.contains(key.name() + " " + key.token())).toList();
    }

    private List<GateKey> orphanUserHoldKeys(long performanceId, List<GateKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Set<String> live = new HashSet<>();
        forEachChunk(tokens(keys), chunk -> live.addAll(
                jdbcTemplate.queryForList(LIVE_USER_HOLD_SQL, params(performanceId, chunk), String.class)));
        return keys.stream().filter(key -> !live.contains(key.token())).toList();
    }

    // 읽은 뒤 값이 바뀐 키(새 선점이 같은 좌석을 잡음)는 release.lua가 값을 비교해 남긴다
    private long release(List<GateKey> orphans) {
        if (orphans.isEmpty()) {
            return 0;
        }
        Map<String, List<String>> keysByToken = new LinkedHashMap<>();
        orphans.forEach(key -> keysByToken.computeIfAbsent(key.token(), t -> new ArrayList<>()).add(key.name()));
        return RedisFailures.translate(() -> {
            long removed = 0;
            for (Map.Entry<String, List<String>> entry : keysByToken.entrySet()) {
                Long n = redis.execute(RedisScripts.RELEASE, entry.getValue(), entry.getKey());
                removed += n == null ? 0 : n;
            }
            return removed;
        });
    }

    private static List<String> tokens(List<GateKey> keys) {
        return keys.stream().map(GateKey::token).distinct().toList();
    }

    private static void forEachChunk(List<String> tokens, Consumer<List<String>> action) {
        for (int from = 0; from < tokens.size(); from += IN_CHUNK) {
            action.accept(tokens.subList(from, Math.min(from + IN_CHUNK, tokens.size())));
        }
    }

    private static MapSqlParameterSource params(long performanceId, List<String> tokens) {
        return new MapSqlParameterSource().addValue("pid", performanceId).addValue("tokens", tokens);
    }

    record Removed(long seats, long userHolds) {
    }

    private record GateKey(String name, String token) {
    }
}
