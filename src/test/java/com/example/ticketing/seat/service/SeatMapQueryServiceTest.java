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
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.SchedulerLease;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
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
    private SeatCatalog seatCatalog;
    @Autowired
    private SeatMapBuilder builder;
    @Autowired
    private SeatMapRedisStore store;
    @Autowired
    private SchedulerLease schedulerLease;
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
    @DisplayName("스냅샷이 없을 때 50건 동시 조회: 재생성(miss) 1회, 50건 모두 같은 generatedAt")
    void concurrentMissRebuildsOnce() throws Exception {
        long pid = performance();
        SeatMapQueryService service = service(store, builder);
        double missBefore = rebuilds("miss");

        List<String> results = runConcurrently(50, () -> service.get(pid));

        Set<Long> generatedAts = results.stream().map(this::generatedAt).collect(Collectors.toSet());
        System.out.println("[S7.1] 동시 50건 miss 재생성 수: " + (rebuilds("miss") - missBefore)
                + ", generatedAt 종류: " + generatedAts);
        assertThat(rebuilds("miss") - missBefore).isEqualTo(1.0);
        assertThat(results).hasSize(50);
        assertThat(generatedAts).hasSize(1);
        assertThat(store.get(pid)).isEqualTo(results.get(0));
        assertThat(bookingRedis.template().hasKey(SeatMapRedisStore.leaseKey(pid))).isFalse();
    }

    @Test
    @DisplayName("Redis에 스냅샷이 있으면 빌드하지 않고 그 값을 그대로 돌려준다")
    void returnsRedisSnapshotAsIs() {
        long pid = performance();
        String stored = "{\"performanceId\":" + pid + ",\"generatedAt\":1,\"sections\":[]}";
        store.put(pid, stored, Duration.ofSeconds(30));
        double missBefore = rebuilds("miss");

        assertThat(service(store, builder).get(pid)).isEqualTo(stored);
        assertThat(rebuilds("miss")).isEqualTo(missBefore);
    }

    @Test
    @DisplayName("로컬 캐시 1초 안에서는 Redis를 다시 읽지 않고, 지나면 다시 읽는다")
    void localCacheSkipsRedisWithinOneSecond() throws InterruptedException {
        long pid = performance();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn("v1", "v2");
        SeatMapQueryService service = service(mockStore, builder);

        assertThat(service.get(pid)).isEqualTo("v1");
        assertThat(service.get(pid)).isEqualTo("v1");
        assertThat(service.get(pid)).isEqualTo("v1");
        verify(mockStore, times(1)).get(pid);

        Thread.sleep(props.seatmap().localCache().toMillis() + 100);
        assertThat(service.get(pid)).isEqualTo("v2");
        verify(mockStore, times(2)).get(pid);
    }

    @Test
    @DisplayName("다른 인스턴스가 리스를 잡고 끝내 저장하지 않으면 50ms×10회 재조회 뒤 503 SEATMAP_UNAVAILABLE")
    void unavailableWhenLeaseHeldElsewhere() {
        long pid = performance();
        bookingRedis.template().opsForValue().set(SeatMapRedisStore.leaseKey(pid), "other-instance", Duration.ofSeconds(5));
        double missBefore = rebuilds("miss");

        long started = System.nanoTime();
        assertThatThrownBy(() -> service(store, builder).get(pid))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SEATMAP_UNAVAILABLE);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).isGreaterThanOrEqualTo(SeatMapQueryService.MAX_POLLS * SeatMapQueryService.POLL_INTERVAL_MS);
        assertThat(rebuilds("miss")).isEqualTo(missBefore);
        assertThat(bookingRedis.template().opsForValue().get(SeatMapRedisStore.leaseKey(pid))).isEqualTo("other-instance");
    }

    @Test
    @DisplayName("리스 대기 중 다른 인스턴스가 저장하면 그 스냅샷을 받는다")
    void pollsUntilOtherInstancePuts() throws Exception {
        long pid = performance();
        bookingRedis.template().opsForValue().set(SeatMapRedisStore.leaseKey(pid), "other-instance", Duration.ofSeconds(5));
        String stored = builder.build(pid).orElseThrow();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = pool.submit(() -> service(store, builder).get(pid));
            Thread.sleep(150);
            store.put(pid, stored, Duration.ofSeconds(30));
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(stored);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Redis 예외 + 1초 지난 로컬 캐시: DB로 다시 빌드(fallback +1, 새 generatedAt)하고, 1초 안의 반복은 다시 빌드하지 않는다")
    void redisFailureRebuildsStaleLocalCache() throws InterruptedException {
        long pid = performance();
        String old = builder.build(pid).orElseThrow();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn(old).thenThrow(new QueryTimeoutException("redis timeout"));
        SeatMapQueryService service = service(mockStore, builder);
        double fallbackBefore = rebuilds("fallback");

        assertThat(service.get(pid)).isEqualTo(old);
        Thread.sleep(props.seatmap().localCache().toMillis() + 100);

        String rebuilt = service.get(pid);
        assertThat(generatedAt(rebuilt)).isGreaterThan(generatedAt(old));
        assertThat(rebuilds("fallback") - fallbackBefore).isEqualTo(1.0);

        for (int i = 0; i < 5; i++) {
            assertThat(service.get(pid)).isEqualTo(rebuilt);
        }
        assertThat(rebuilds("fallback") - fallbackBefore).isEqualTo(1.0);
        verify(mockStore, times(2)).get(pid);
    }

    @Test
    @DisplayName("Redis 예외 + 1초 지난 로컬 캐시 + DB 빌드 실패: 오래된 캐시를 돌려주고, 1초 안에는 DB를 다시 읽지 않는다")
    void redisFailureKeepsStaleCacheWhenBuildFails() throws InterruptedException {
        long pid = performance();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn("cached-v1").thenThrow(new QueryTimeoutException("redis timeout"));
        SeatMapBuilder failingBuilder = mock(SeatMapBuilder.class);
        when(failingBuilder.build(pid)).thenThrow(new DataAccessResourceFailureException("db down"));
        SeatMapQueryService service = service(mockStore, failingBuilder);

        assertThat(service.get(pid)).isEqualTo("cached-v1");
        Thread.sleep(props.seatmap().localCache().toMillis() + 100);

        assertThat(service.get(pid)).isEqualTo("cached-v1");
        assertThat(service.get(pid)).isEqualTo("cached-v1");
        verify(failingBuilder, times(1)).build(pid);

        Thread.sleep(props.seatmap().localCache().toMillis() + 100);
        assertThat(service.get(pid)).isEqualTo("cached-v1");
        verify(failingBuilder, times(2)).build(pid);
    }

    @Test
    @DisplayName("Redis 예외 + 1초 지난 로컬 캐시 + 동시 20건: fallback 빌드는 1번, 20건 모두 새 generatedAt 하나")
    void concurrentStaleFallbackIsSingleFlight() throws Exception {
        long pid = performance();
        String old = builder.build(pid).orElseThrow();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn(old).thenThrow(new QueryTimeoutException("redis timeout"));
        SeatMapQueryService service = service(mockStore, slowBuilder(200));
        assertThat(service.get(pid)).isEqualTo(old);
        Thread.sleep(props.seatmap().localCache().toMillis() + 100);
        double fallbackBefore = rebuilds("fallback");

        List<String> results = runConcurrently(20, () -> service.get(pid));

        Set<Long> generatedAts = results.stream().map(this::generatedAt).collect(Collectors.toSet());
        System.out.println("[S9.1] 오래된 캐시 + 동시 20건 fallback 재생성 수: " + (rebuilds("fallback") - fallbackBefore));
        assertThat(rebuilds("fallback") - fallbackBefore).isEqualTo(1.0);
        assertThat(generatedAts).hasSize(1).doesNotContain(generatedAt(old));
    }

    @Test
    @DisplayName("Redis 예외 + 로컬 캐시 없음: DB로 빌드(fallback)하고 로컬 캐시에 넣는다")
    void redisFailureWithoutLocalCacheBuildsFromDb() {
        long pid = performance();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(anyLong())).thenThrow(new QueryTimeoutException("redis timeout"));
        SeatMapQueryService service = service(mockStore, builder);
        double fallbackBefore = rebuilds("fallback");

        String json = service.get(pid);

        assertThat(jsonMapper.readTree(json).get("performanceId").asLong()).isEqualTo(pid);
        assertThat(rebuilds("fallback") - fallbackBefore).isEqualTo(1.0);
        assertThat(service.get(pid)).isEqualTo(json);
        verify(mockStore, times(1)).get(pid);
        assertThat(rebuilds("fallback") - fallbackBefore).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 예외 + 로컬 캐시 없음 + 동시 20건: fallback 빌드는 1번, 20건 모두 같은 generatedAt")
    void concurrentFallbackIsSingleFlight() throws Exception {
        long pid = performance();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(anyLong())).thenThrow(new QueryTimeoutException("redis timeout"));
        SeatMapQueryService service = service(mockStore, slowBuilder(200));
        double fallbackBefore = rebuilds("fallback");

        List<String> results = runConcurrently(20, () -> service.get(pid));

        Set<Long> generatedAts = results.stream().map(this::generatedAt).collect(Collectors.toSet());
        System.out.println("[S7.1] 동시 20건 fallback 재생성 수: " + (rebuilds("fallback") - fallbackBefore));
        assertThat(rebuilds("fallback") - fallbackBefore).isEqualTo(1.0);
        assertThat(results).hasSize(20);
        assertThat(generatedAts).hasSize(1);
    }

    @Test
    @DisplayName("tryLease에서 Redis 예외가 나도 fallback 빌드로 넘어간다")
    void redisFailureOnLeaseFallsBack() {
        long pid = performance();
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);
        when(mockStore.get(pid)).thenReturn(null);
        when(mockStore.tryLease(eq(pid), anyString(), any())).thenThrow(new QueryTimeoutException("redis timeout"));
        double fallbackBefore = rebuilds("fallback");

        String json = service(mockStore, builder).get(pid);

        assertThat(jsonMapper.readTree(json).get("performanceId").asLong()).isEqualTo(pid);
        assertThat(rebuilds("fallback") - fallbackBefore).isEqualTo(1.0);
    }

    @Test
    @DisplayName("없는 회차는 404 PERFORMANCE_NOT_FOUND이고 Redis를 읽지 않는다")
    void unknownPerformance() {
        SeatMapRedisStore mockStore = mock(SeatMapRedisStore.class);

        assertThatThrownBy(() -> service(mockStore, builder).get(Long.MAX_VALUE))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.PERFORMANCE_NOT_FOUND);
        verify(mockStore, times(0)).get(anyLong());
    }

    @Test
    @DisplayName("좌석이 없는 회차는 빈 좌석맵 대신 503 SEATMAP_UNAVAILABLE")
    void emptySeatMapIsNotReturned() {
        long pid = performance();
        jdbcTemplate.update("DELETE FROM seat WHERE performance_id = ?", pid);
        assertThatThrownBy(() -> service(store, builder).get(pid))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SEATMAP_UNAVAILABLE);
        assertThat(store.get(pid)).isNull();
    }

    private SeatMapQueryService service(SeatMapRedisStore seatMapStore, SeatMapBuilder seatMapBuilder) {
        return new SeatMapQueryService(seatCatalog, seatMapBuilder, seatMapStore, schedulerLease, props, meterRegistry);
    }

    // 동시 요청이 single-flight 안에서 겹치도록 빌드를 늦춘다
    private SeatMapBuilder slowBuilder(long delayMs) {
        return new SeatMapBuilder(jdbcTemplate, jsonMapper) {
            @Override
            public Optional<String> build(long performanceId) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.build(performanceId);
            }
        };
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long generatedAt(String json) {
        return jsonMapper.readTree(json).get("generatedAt").asLong();
    }

    private double rebuilds(String source) {
        return meterRegistry.counter(Metrics.SEATMAP_REBUILD, Tag.SOURCE, source).count();
    }

    private long performance() {
        long pid = fixtures.newPerformance(1, 2, 5);
        pids.add(pid);
        return pid;
    }
}
