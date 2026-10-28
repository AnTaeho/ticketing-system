package com.example.ticketing.seat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.InstanceId;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TestTicketingRedis;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import com.example.ticketing.support.CapturedLogs;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
    private SeatCatalog seatCatalog;
    @Autowired
    private SeatMapBuilder builder;
    @Autowired
    private SeatMapRedisStore store;
    @Autowired
    private TicketingProperties props;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private RedisKeyCleaner cleaner;
    @Autowired
    private TicketingRedis bookingRedis;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    private final List<Long> pids = new ArrayList<>();

    @AfterEach
    void tearDown() {
        pids.forEach(cleaner::clean);
    }

    @Test
    @DisplayName("리스를 가진 인스턴스만 빌드한다. 리더는 다음 주기에 리스를 이어 가고, 다른 인스턴스는 건너뛴다")
    void onlyLeaseHolderBuilds() {
        long pid = openPerformance();
        InstanceId instanceA = new InstanceId();
        InstanceId instanceB = new InstanceId();
        SeatMapSnapshotScheduler a = scheduler(instanceA, catalogOf(List.of(pid)));
        SeatMapSnapshotScheduler b = scheduler(instanceB, catalogOf(List.of(pid)));
        double before = rebuilds();

        a.snapshot();
        assertThat(rebuilds() - before).isEqualTo(1.0);
        String first = store.get(pid);
        assertThat(jsonMapper.readTree(first).get("performanceId").asLong()).isEqualTo(pid);
        assertThat(redis().opsForValue().get(RedisKeys.seatMapLease(pid))).isEqualTo(instanceA.value());
        assertThat(redis().getExpire(RedisKeys.seatMap(pid), TimeUnit.MILLISECONDS))
                .isPositive().isLessThanOrEqualTo(props.seatmap().snapshotTtl().toMillis());

        b.snapshot();
        assertThat(rebuilds() - before).isEqualTo(1.0);
        assertThat(store.get(pid)).isEqualTo(first);

        a.snapshot();
        assertThat(rebuilds() - before).isEqualTo(2.0);
        assertThat(redis().getExpire(RedisKeys.seatMapLease(pid), TimeUnit.MILLISECONDS))
                .isPositive().isLessThanOrEqualTo(props.seatmap().leaseTtl().toMillis());

        // 리더가 멈춰 리스가 풀리면 다른 인스턴스가 이어받는다
        redis().delete(RedisKeys.seatMapLease(pid));
        b.snapshot();
        assertThat(rebuilds() - before).isEqualTo(3.0);
        assertThat(redis().opsForValue().get(RedisKeys.seatMapLease(pid))).isEqualTo(instanceB.value());
    }

    @Test
    @DisplayName("예매 중이 아닌 회차(열리기 전·시작 후)는 건너뛴다")
    void skipsPerformancesNotOpen() {
        long open = openPerformance();
        LocalDateTime now = fixtures.dbNow();
        long notYetOpen = track(fixtures.newPerformance(fixtures.newShow(), now.plusDays(1), now.plusDays(2), 1, 1, 2));
        long started = track(fixtures.newPerformance(fixtures.newShow(), now.minusDays(2), now.minusMinutes(1), 1, 1, 2));
        List<Long> mine = List.of(open, notYetOpen, started);
        // 운영 카탈로그는 예매 중 목록을 10초 묶어 두므로, 같은 쿼리를 쓰는 새 카탈로그에서 이 테스트의 회차만 남긴다
        SeatCatalog fresh = new SeatCatalog(jdbcTemplate, props, System::nanoTime) {
            @Override
            public List<Long> openPerformanceIds() {
                return super.openPerformanceIds().stream().filter(mine::contains).toList();
            }
        };

        scheduler(new InstanceId(), fresh).snapshot();

        assertThat(store.get(open)).isNotNull();
        assertThat(store.get(notYetOpen)).isNull();
        assertThat(store.get(started)).isNull();
        assertThat(redis().hasKey(RedisKeys.seatMapLease(notYetOpen))).isFalse();
        assertThat(redis().hasKey(RedisKeys.seatMapLease(started))).isFalse();
    }

    @Test
    @DisplayName("한 회차가 예외를 내도 다음 회차는 스냅샷을 만든다")
    void oneFailingPerformanceDoesNotStopOthers() {
        long broken = openPerformance();
        long healthy = openPerformance();
        redis().opsForHash().put(RedisKeys.seatMapLease(broken), "not", "a-string");

        scheduler(new InstanceId(), catalogOf(List.of(broken, healthy))).snapshot();

        assertThat(store.get(broken)).isNull();
        assertThat(store.get(healthy)).isNotNull();
    }

    @Test
    @DisplayName("Redis 장애로 여러 회차가 여러 주기 실패해도 10초 안에는 warn이 한 줄이다")
    void failureWarnThrottled() throws IOException {
        TicketingRedis dead = TestTicketingRedis.connectTo("localhost", closedPort(), props);
        SeatMapSnapshotScheduler scheduler = new SeatMapSnapshotScheduler(catalogOf(List.of(1L, 2L, 3L)), builder,
                new SeatMapRedisStore(dead), new InstanceId(), System::nanoTime, props, meterRegistry);
        try (CapturedLogs logs = CapturedLogs.of(SeatMapSnapshotScheduler.class)) {
            for (int cycle = 0; cycle < 3; cycle++) {
                scheduler.snapshot();
            }

            assertThat(logs.warnings()).hasSize(1);
            assertThat(logs.warnings().get(0)).contains("회차 1 스냅샷 실패").contains("앞서 생략 0건");
        } finally {
            dead.destroy();
        }
    }

    @Test
    @DisplayName("releaseLease는 값이 소유자와 같을 때만 지운다")
    void releaseLeaseComparesOwner() {
        long pid = openPerformance();
        assertThat(store.tryLease(pid, "owner-1", Duration.ofSeconds(2))).isTrue();
        assertThat(store.tryLease(pid, "owner-2", Duration.ofSeconds(2))).isFalse();

        store.releaseLease(pid, "owner-2");
        assertThat(redis().opsForValue().get(RedisKeys.seatMapLease(pid))).isEqualTo("owner-1");

        store.releaseLease(pid, "owner-1");
        assertThat(redis().hasKey(RedisKeys.seatMapLease(pid))).isFalse();
        assertThat(store.tryLease(pid, "owner-2", Duration.ofSeconds(2))).isTrue();
    }

    private SeatMapSnapshotScheduler scheduler(InstanceId instanceId, SeatCatalog catalog) {
        return new SeatMapSnapshotScheduler(catalog, builder, store, instanceId, System::nanoTime, props,
                meterRegistry);
    }

    private SeatCatalog catalogOf(List<Long> openIds) {
        return new SeatCatalog(jdbcTemplate, props, System::nanoTime) {
            @Override
            public List<Long> openPerformanceIds() {
                return openIds;
            }
        };
    }

    private double rebuilds() {
        return meterRegistry.counter(Metrics.SEATMAP_REBUILD, Tag.SOURCE, "scheduler").count();
    }

    private long openPerformance() {
        return track(fixtures.newPerformance(1, 1, 3));
    }

    private long track(long pid) {
        pids.add(pid);
        return pid;
    }

    private StringRedisTemplate redis() {
        return bookingRedis.template();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
