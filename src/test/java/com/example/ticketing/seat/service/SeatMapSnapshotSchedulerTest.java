package com.example.ticketing.seat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.RebuildSource;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.InstanceId;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import com.example.ticketing.support.CapturedLogs;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// 스케줄러 빈은 테스트에서 꺼져 있으므로 인스턴스 ID를 달리해 new로 만들고 주기 메서드를 직접 부른다
@IntegrationTest
class SeatMapSnapshotSchedulerTest {

    @Autowired
    private SeatMapRedisStore seatMapRedisStore;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestServices services;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private Counters counters;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private TestDb db;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis = testRedis.template();
    }

    @Test
    @DisplayName("리스를 쥔 인스턴스만 빌드하고 리스가 풀리면 다른 인스턴스가 이어받는다")
    void onlyLeaseHolderBuilds() {
        long pid = fixtures.newPerformance(1, 1, 3);
        InstanceId instanceA = new InstanceId();
        InstanceId instanceB = new InstanceId();
        SeatMapSnapshotScheduler a = services.seatMapSnapshotScheduler(instanceA, services.catalogOf(List.of(pid)));
        SeatMapSnapshotScheduler b = services.seatMapSnapshotScheduler(instanceB, services.catalogOf(List.of(pid)));
        double before = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.SCHEDULER);

        a.run();
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.SCHEDULER) - before)
                .isEqualTo(1.0);
        String first = seatMapRedisStore.get(pid);
        assertThat(jsonMapper.readTree(first).get("performanceId").asLong()).isEqualTo(pid);
        assertThat(redis.opsForValue().get(RedisKeys.seatMapLease(pid))).isEqualTo(instanceA.value());
        assertThat(testRedis.pttl(RedisKeys.seatMap(pid)))
                .isPositive().isLessThanOrEqualTo(properties.seatmap().snapshotTtl().toMillis());

        b.run();
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.SCHEDULER) - before)
                .isEqualTo(1.0);
        assertThat(seatMapRedisStore.get(pid)).isEqualTo(first);

        a.run();
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.SCHEDULER) - before)
                .isEqualTo(2.0);
        assertThat(testRedis.pttl(RedisKeys.seatMapLease(pid)))
                .isPositive().isLessThanOrEqualTo(properties.seatmap().leaseTtl().toMillis());

        // 리더가 멈춰 리스가 풀리면 다른 인스턴스가 이어받는다
        redis.delete(RedisKeys.seatMapLease(pid));
        b.run();
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.SCHEDULER) - before)
                .isEqualTo(3.0);
        assertThat(redis.opsForValue().get(RedisKeys.seatMapLease(pid))).isEqualTo(instanceB.value());
    }

    @Test
    @DisplayName("열리기 전이거나 이미 시작한 회차는 건너뛴다")
    void skipsPerformancesNotOpen() {
        long open = fixtures.newPerformance(1, 1, 3);
        LocalDateTime now = fixtures.dbNow();
        long notYetOpen = fixtures.newPerformance(fixtures.newShow(), now.plusDays(1), now.plusDays(2), 1, 1, 2);
        long started = fixtures.newPerformance(fixtures.newShow(), now.minusDays(2), now.minusMinutes(1), 1, 1, 2);
        SeatCatalog catalog = services.openCatalogAmong(List.of(open, notYetOpen, started));

        services.seatMapSnapshotScheduler(new InstanceId(), catalog).run();

        assertThat(seatMapRedisStore.get(open)).isNotNull();
        assertThat(seatMapRedisStore.get(notYetOpen)).isNull();
        assertThat(seatMapRedisStore.get(started)).isNull();
        assertThat(redis.hasKey(RedisKeys.seatMapLease(notYetOpen))).isFalse();
        assertThat(redis.hasKey(RedisKeys.seatMapLease(started))).isFalse();
    }

    @Test
    @DisplayName("한 회차가 예외를 내도 다음 회차는 스냅샷을 만든다")
    void oneFailingPerformanceDoesNotStopOthers() {
        long broken = fixtures.newPerformance(1, 1, 3);
        long healthy = fixtures.newPerformance(1, 1, 3);
        redis.opsForHash().put(RedisKeys.seatMapLease(broken), "not", "a-string");

        services.seatMapSnapshotScheduler(new InstanceId(), services.catalogOf(List.of(broken, healthy))).run();

        assertThat(seatMapRedisStore.get(broken)).isNull();
        assertThat(seatMapRedisStore.get(healthy)).isNotNull();
    }

    @Test
    @DisplayName("Redis 장애로 여러 회차가 여러 주기 실패해도 10초 안에는 warn이 한 줄이다")
    void failureWarnThrottled() {
        SeatMapSnapshotScheduler scheduler = services.seatMapSnapshotScheduler(new InstanceId(),
                services.catalogOf(List.of(1L, 2L, 3L)), TestServices.seatMapStore(testRedis.dead()));

        try (CapturedLogs logs = CapturedLogs.of(SeatMapSnapshotScheduler.class)) {
            for (int cycle = 0; cycle < 3; cycle++) {
                scheduler.run();
            }

            assertThat(logs.warnings()).hasSize(1);
            assertThat(logs.warnings().get(0)).contains("스냅샷 실패").contains("performanceId=1")
                    .contains("앞서 생략 0건");
        }
    }

    @Test
    @DisplayName("모든 좌석이 SOLD면 스냅샷과 함께 매진 표시를 쓰고, HELD가 하나라도 남으면 매진이 아니다")
    void writesSoldOutFlagWithSnapshot() {
        long pid = fixtures.newPerformance(1, 1, 3);
        List<Long> seatIds = db.seatIds(pid);
        SeatMapSnapshotScheduler scheduler = services.seatMapSnapshotScheduler(new InstanceId(),
                services.catalogOf(List.of(pid)));
        assertThat(seatMapRedisStore.isSoldOut(pid)).as("표시가 없으면 매진이 아니다").isFalse();

        scheduler.run();
        assertThat(seatMapRedisStore.isSoldOut(pid)).isFalse();
        assertThat(redis.opsForValue().get(RedisKeys.seatMapSoldOut(pid))).isEqualTo("0");

        jdbcTemplate.update("UPDATE seat SET status = 'SOLD' WHERE id IN (?, ?)", seatIds.get(0), seatIds.get(1));
        jdbcTemplate.update("UPDATE seat SET status = 'HELD', held_until = NOW(6) + INTERVAL 5 MINUTE WHERE id = ?",
                seatIds.get(2));
        scheduler.run();
        assertThat(seatMapRedisStore.isSoldOut(pid)).as("HELD는 다시 풀릴 수 있다").isFalse();

        jdbcTemplate.update("UPDATE seat SET status = 'SOLD', held_until = NULL WHERE id = ?", seatIds.get(2));
        scheduler.run();
        assertThat(seatMapRedisStore.isSoldOut(pid)).isTrue();
        assertThat(testRedis.pttl(RedisKeys.seatMapSoldOut(pid)))
                .isPositive().isLessThanOrEqualTo(properties.seatmap().snapshotTtl().toMillis());
    }

    @Test
    @DisplayName("리스 해제(releaseLease)는 값이 소유자와 같을 때만 지운다")
    void releaseLeaseComparesOwner() {
        long pid = fixtures.newPerformance(1, 1, 3);
        assertThat(seatMapRedisStore.tryLease(pid, "owner-1", Duration.ofSeconds(2))).isTrue();
        assertThat(seatMapRedisStore.tryLease(pid, "owner-2", Duration.ofSeconds(2))).isFalse();

        seatMapRedisStore.releaseLease(pid, "owner-2");
        assertThat(redis.opsForValue().get(RedisKeys.seatMapLease(pid))).isEqualTo("owner-1");

        seatMapRedisStore.releaseLease(pid, "owner-1");
        assertThat(redis.hasKey(RedisKeys.seatMapLease(pid))).isFalse();
        assertThat(seatMapRedisStore.tryLease(pid, "owner-2", Duration.ofSeconds(2))).isTrue();
    }
}
