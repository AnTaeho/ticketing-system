package com.example.ticketing.seat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.RebuildSource;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Concurrently.Running;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// 로컬 캐시가 테스트 사이에 남지 않도록 서비스는 매번 new로 만든다. Redis 장애는 목 SeatMapRedisStore로 재현해 컨텍스트를 늘리지 않는다
@IntegrationTest
class SeatMapQueryServiceTest {

    @Autowired
    private SeatMapBuilder seatMapBuilder;
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("스냅샷이 없을 때 50건이 동시에 조회하면 한 번만 다시 만든다")
    void concurrentMissRebuildsOnce() {
        long pid = fixtures.newPerformance(1, 2, 5);
        SeatMapQueryService service = services.seatMapQueryService(seatMapRedisStore, seatMapBuilder);
        double missBefore = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.MISS);

        Results<String> results = Concurrently.of(50).within(Duration.ofSeconds(10)).run(i -> service.get(pid));

        assertThat(results.errors()).isEmpty();
        List<String> snapshots = results.values();
        Set<Long> generatedAts = snapshots.stream().map(this::generatedAt).collect(Collectors.toSet());
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.MISS) - missBefore)
                .isEqualTo(1.0);
        assertThat(snapshots).hasSize(50);
        assertThat(generatedAts).hasSize(1);
        assertThat(seatMapRedisStore.get(pid)).isEqualTo(snapshots.get(0));
        assertThat(testRedis.template().hasKey(RedisKeys.seatMapLease(pid))).isFalse();
    }

    @Test
    @DisplayName("Redis에 스냅샷이 있으면 빌드하지 않고 그 값을 그대로 돌려준다")
    void returnsRedisSnapshotAsIs() {
        long pid = fixtures.newPerformance(1, 2, 5);
        String stored = "{\"performanceId\":" + pid + ",\"generatedAt\":1,\"sections\":[]}";
        seatMapRedisStore.put(pid, stored, Duration.ofSeconds(30));
        double missBefore = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.MISS);

        String snapshot = services.seatMapQueryService(seatMapRedisStore, seatMapBuilder).get(pid);

        assertThat(snapshot).isEqualTo(stored);
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.MISS)).isEqualTo(missBefore);
    }

    @Test
    @DisplayName("로컬 캐시 1초 안에는 Redis를 다시 읽지 않고 지나면 다시 읽는다")
    void localCacheSkipsRedisWithinOneSecond() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 2, 5);
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn("v1", "v2");
        SeatMapQueryService service = services.seatMapQueryService(mockStore, seatMapBuilder);

        assertThat(service.get(pid)).isEqualTo("v1");
        assertThat(service.get(pid)).isEqualTo("v1");
        assertThat(service.get(pid)).isEqualTo("v1");
        verify(mockStore, times(1)).get(pid);

        Thread.sleep(properties.seatmap().localCache().toMillis() + 100);
        assertThat(service.get(pid)).isEqualTo("v2");
        verify(mockStore, times(2)).get(pid);
    }

    @Test
    @DisplayName("다른 인스턴스가 리스를 쥔 채 저장하지 않으면 재조회 끝에 503이다")
    void unavailableWhenLeaseHeldElsewhere() {
        long pid = fixtures.newPerformance(1, 2, 5);
        testRedis.template().opsForValue().set(RedisKeys.seatMapLease(pid), "other-instance", Duration.ofSeconds(5));
        double missBefore = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.MISS);
        SeatMapQueryService service = services.seatMapQueryService(seatMapRedisStore, seatMapBuilder);

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.get(pid))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SEATMAP_UNAVAILABLE);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs)
                .isGreaterThanOrEqualTo(SeatMapQueryService.MAX_POLLS * SeatMapQueryService.POLL_INTERVAL_MS);
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.MISS)).isEqualTo(missBefore);
        assertThat(testRedis.template().opsForValue().get(RedisKeys.seatMapLease(pid))).isEqualTo("other-instance");
    }

    @Test
    @DisplayName("리스를 기다리는 중 다른 인스턴스가 저장하면 그 스냅샷을 받는다")
    void pollsUntilOtherInstancePuts() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 2, 5);
        testRedis.template().opsForValue().set(RedisKeys.seatMapLease(pid), "other-instance", Duration.ofSeconds(5));
        String stored = seatMapBuilder.build(pid).orElseThrow();
        SeatMapQueryService service = services.seatMapQueryService(seatMapRedisStore, seatMapBuilder);

        Results<String> results;
        try (Running<String> running = Concurrently.of(1).within(Duration.ofSeconds(5)).start(i -> service.get(pid))) {
            Thread.sleep(150);
            seatMapRedisStore.put(pid, stored, Duration.ofSeconds(30));
            results = running.await();
        }

        assertThat(results.errors()).isEmpty();
        assertThat(results.values()).containsExactly(stored);
    }

    @Test
    @DisplayName("Redis가 죽고 로컬 캐시가 낡았으면 DB로 한 번 다시 만들어 1초 동안 쓴다")
    void redisFailureRebuildsStaleLocalCache() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 2, 5);
        String old = seatMapBuilder.build(pid).orElseThrow();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn(old).thenThrow(redisDown());
        SeatMapQueryService service = services.seatMapQueryService(mockStore, seatMapBuilder);
        double fallbackBefore = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK);

        assertThat(service.get(pid)).isEqualTo(old);
        Thread.sleep(properties.seatmap().localCache().toMillis() + 100);

        String rebuilt = service.get(pid);
        assertThat(generatedAt(rebuilt)).isGreaterThan(generatedAt(old));
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK) - fallbackBefore)
                .isEqualTo(1.0);

        for (int i = 0; i < 5; i++) {
            assertThat(service.get(pid)).isEqualTo(rebuilt);
        }
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK) - fallbackBefore)
                .isEqualTo(1.0);
        verify(mockStore, times(2)).get(pid);
    }

    @Test
    @DisplayName("Redis와 DB가 모두 실패하면 낡은 캐시를 주고 1초 안에는 DB를 다시 읽지 않는다")
    void redisFailureKeepsStaleCacheWhenBuildFails() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 2, 5);
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn("cached-v1").thenThrow(redisDown());
        SeatMapBuilder failingBuilder = mock(SeatMapBuilder.class);
        when(failingBuilder.build(pid)).thenThrow(new DataAccessResourceFailureException("db down"));
        SeatMapQueryService service = services.seatMapQueryService(mockStore, failingBuilder);

        assertThat(service.get(pid)).isEqualTo("cached-v1");
        Thread.sleep(properties.seatmap().localCache().toMillis() + 100);

        assertThat(service.get(pid)).isEqualTo("cached-v1");
        assertThat(service.get(pid)).isEqualTo("cached-v1");
        verify(failingBuilder, times(1)).build(pid);

        Thread.sleep(properties.seatmap().localCache().toMillis() + 100);
        assertThat(service.get(pid)).isEqualTo("cached-v1");
        verify(failingBuilder, times(2)).build(pid);
    }

    @Test
    @DisplayName("Redis가 죽고 로컬 캐시가 낡았을 때 20건이 동시에 오면 한 번만 다시 만든다")
    void concurrentStaleFallbackIsSingleFlight() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 2, 5);
        String old = seatMapBuilder.build(pid).orElseThrow();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn(old).thenThrow(redisDown());
        SeatMapQueryService service = services.seatMapQueryService(mockStore, services.slowSeatMapBuilder(200));
        assertThat(service.get(pid)).isEqualTo(old);
        Thread.sleep(properties.seatmap().localCache().toMillis() + 100);
        double fallbackBefore = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK);

        Results<String> results = Concurrently.of(20).within(Duration.ofSeconds(10)).run(i -> service.get(pid));

        assertThat(results.errors()).isEmpty();
        Set<Long> generatedAts = results.values().stream().map(this::generatedAt).collect(Collectors.toSet());
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK) - fallbackBefore)
                .isEqualTo(1.0);
        assertThat(generatedAts).hasSize(1).doesNotContain(generatedAt(old));
    }

    @Test
    @DisplayName("Redis가 죽고 로컬 캐시가 없으면 DB로 만들어 로컬 캐시에 넣는다")
    void redisFailureWithoutLocalCacheBuildsFromDb() {
        long pid = fixtures.newPerformance(1, 2, 5);
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(anyLong())).thenThrow(redisDown());
        SeatMapQueryService service = services.seatMapQueryService(mockStore, seatMapBuilder);
        double fallbackBefore = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK);

        String json = service.get(pid);

        assertThat(jsonMapper.readTree(json).get("performanceId").asLong()).isEqualTo(pid);
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK) - fallbackBefore)
                .isEqualTo(1.0);
        assertThat(service.get(pid)).isEqualTo(json);
        verify(mockStore, times(1)).get(pid);
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK) - fallbackBefore)
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis가 죽고 로컬 캐시가 없을 때 20건이 동시에 오면 한 번만 만든다")
    void concurrentFallbackIsSingleFlight() {
        long pid = fixtures.newPerformance(1, 2, 5);
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(anyLong())).thenThrow(redisDown());
        SeatMapQueryService service = services.seatMapQueryService(mockStore, services.slowSeatMapBuilder(200));
        double fallbackBefore = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK);

        Results<String> results = Concurrently.of(20).within(Duration.ofSeconds(10)).run(i -> service.get(pid));

        assertThat(results.errors()).isEmpty();
        List<String> snapshots = results.values();
        Set<Long> generatedAts = snapshots.stream().map(this::generatedAt).collect(Collectors.toSet());
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK) - fallbackBefore)
                .isEqualTo(1.0);
        assertThat(snapshots).hasSize(20);
        assertThat(generatedAts).hasSize(1);
    }

    @Test
    @DisplayName("리스를 잡다가(tryLease) Redis 예외가 나도 DB 빌드로 넘어간다")
    void redisFailureOnLeaseFallsBack() {
        long pid = fixtures.newPerformance(1, 2, 5);
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn(null);
        when(mockStore.tryLease(eq(pid), anyString(), any())).thenThrow(redisDown());
        double fallbackBefore = counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK);

        String json = services.seatMapQueryService(mockStore, seatMapBuilder).get(pid);

        assertThat(jsonMapper.readTree(json).get("performanceId").asLong()).isEqualTo(pid);
        assertThat(counters.count(Metrics.SEATMAP_REBUILD, Tag.SOURCE, RebuildSource.FALLBACK) - fallbackBefore)
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("없는 회차는 Redis를 읽지 않고 404다")
    void unknownPerformance() {
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        SeatMapQueryService service = services.seatMapQueryService(mockStore, seatMapBuilder);

        assertThatThrownBy(() -> service.get(Long.MAX_VALUE))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.PERFORMANCE_NOT_FOUND);

        verify(mockStore, times(0)).get(anyLong());
    }

    @Test
    @DisplayName("좌석이 없는 회차는 빈 좌석맵 대신 503이다")
    void emptySeatMapIsNotReturned() {
        long pid = fixtures.newPerformance(1, 2, 5);
        jdbcTemplate.update("DELETE FROM seat WHERE performance_id = ?", pid);
        SeatMapQueryService service = services.seatMapQueryService(seatMapRedisStore, seatMapBuilder);

        assertThatThrownBy(() -> service.get(pid))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SEATMAP_UNAVAILABLE);

        assertThat(seatMapRedisStore.get(pid)).isNull();
    }

    private static RedisUnavailableException redisDown() {
        return new RedisUnavailableException(new QueryTimeoutException("redis timeout"));
    }

    private long generatedAt(String json) {
        return jsonMapper.readTree(json).get("generatedAt").asLong();
    }
}
