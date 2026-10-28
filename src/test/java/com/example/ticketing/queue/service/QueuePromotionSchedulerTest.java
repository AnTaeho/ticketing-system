package com.example.ticketing.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

// 스케줄러 빈은 테스트에서 꺼져 있으므로 정원을 바꿔 new로 만들고 대상 메서드를 직접 부른다
@IntegrationTest
class QueuePromotionSchedulerTest {

    private static final long USER_BASE = 7_100_000_000L;
    private static final String LEASE_KEY = "sched:lease:" + QueuePromotionScheduler.LEASE;

    @Autowired
    private QueueRedisStore queueRedisStore;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private TestServices services;

    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis = testRedis.template();
    }

    // 리스 키에는 회차 태그가 없어 픽스처 정리에 걸리지 않는다
    @AfterEach
    void tearDown() {
        if ("other-instance".equals(redis.opsForValue().get(LEASE_KEY))) {
            redis.delete(LEASE_KEY);
        }
    }

    @Test
    @DisplayName("처리열에서 3명이 빠지면 대기열 앞 3명이 도착 순서대로 올라간다")
    void promotesFifo() {
        long pid = openPerformance();
        QueueService service = service(3);
        QueuePromotionScheduler scheduler = scheduler(3, seatCatalog);
        List<String> admitted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            admitted.add(service.enter(USER_BASE + i, pid).queueToken());
        }
        // 사용자 ID를 거꾸로 줘 같은 ms 진입에서 토큰 사전순과 도착 순서가 갈리게 한다
        List<Long> waitingUsers = List.of(USER_BASE + 900, USER_BASE + 800, USER_BASE + 700,
                USER_BASE + 600, USER_BASE + 500);
        List<String> waiting = new ArrayList<>();
        for (long userId : waitingUsers) {
            waiting.add(service.enter(userId, pid).queueToken());
        }
        assertThat(scheduler.promoteOne(pid)).isZero();

        for (int i = 0; i < 3; i++) {
            assertThat(service.consume(USER_BASE + i, pid, admitted.get(i))).isTrue();
        }
        assertThat(scheduler.promoteOne(pid)).isEqualTo(3);

        for (int i = 0; i < 3; i++) {
            assertThat(service.isAdmitted(waitingUsers.get(i), pid, waiting.get(i))).isTrue();
        }
        assertThat(service.status(waitingUsers.get(3), pid, waiting.get(3)).position()).isEqualTo(1);
        assertThat(service.status(waitingUsers.get(4), pid, waiting.get(4)).position()).isEqualTo(2);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(3);
        assertThat(scheduler.promoteOne(pid)).isZero();
    }

    @Test
    @DisplayName("만료된 처리열 토큰은 바로 막히고 정리되면 다음 사람이 올라간다")
    void expiredProcessingFreesSlot() {
        long pid = openPerformance();
        QueueService service = service(2);
        QueuePromotionScheduler scheduler = scheduler(2, seatCatalog);
        String expired = service.enter(USER_BASE + 1, pid).queueToken();
        String alive = service.enter(USER_BASE + 2, pid).queueToken();
        String next = service.enter(USER_BASE + 3, pid).queueToken();
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), expired, System.currentTimeMillis() - 1);

        assertThat(service.isAdmitted(USER_BASE + 1, pid, expired)).isFalse();
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(2);

        assertThat(scheduler.promoteOne(pid)).isEqualTo(1);
        assertThat(queueRedisStore.processingScore(pid, expired)).isNull();
        assertThat(service.isAdmitted(USER_BASE + 2, pid, alive)).isTrue();
        assertThat(service.isAdmitted(USER_BASE + 3, pid, next)).isTrue();
    }

    @Test
    @DisplayName("대기 수명이 지난 토큰은 정리되고 그 뒤 사람이 올라간다")
    void expiredWaitingIsPurgedBeforePromotion() {
        long pid = openPerformance();
        QueueService service = service(1);
        QueuePromotionScheduler scheduler = scheduler(1, seatCatalog);
        String first = service.enter(USER_BASE + 1, pid).queueToken();
        String stale = service.enter(USER_BASE + 2, pid).queueToken();
        String fresh = service.enter(USER_BASE + 3, pid).queueToken();
        redis.opsForZSet().add(RedisKeys.queueWaiting(pid), stale,
                System.currentTimeMillis() - properties.queue().waitingTokenTtl().toMillis() - 1);
        service.consume(USER_BASE + 1, pid, first);

        assertThat(scheduler.promoteOne(pid)).isEqualTo(1);
        assertThat(queueRedisStore.waitingScore(pid, stale)).isNull();
        assertThat(queueRedisStore.processingScore(pid, stale)).isNull();
        assertThat(service.isAdmitted(USER_BASE + 3, pid, fresh)).isTrue();
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isZero();
    }

    @Test
    @DisplayName("승격할 때마다 처리 속도를 지수 평균으로 갱신한다")
    void updatesRate() {
        long pid = openPerformance();
        QueueService service = service(3);
        QueuePromotionScheduler scheduler = scheduler(3, seatCatalog);
        List<String> admitted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            admitted.add(service.enter(USER_BASE + i, pid).queueToken());
        }
        for (int i = 3; i < 6; i++) {
            service.enter(USER_BASE + i, pid);
        }
        for (int i = 0; i < 3; i++) {
            service.consume(USER_BASE + i, pid, admitted.get(i));
        }
        double interval = properties.queue().promoteIntervalMs() / 1000.0;

        assertThat(scheduler.promoteOne(pid)).isEqualTo(3);
        double first = 0.2 * 3 / interval;
        assertThat(queueRedisStore.rate(pid)).isCloseTo(first, within(1e-9));
        assertThat(testRedis.pttl(RedisKeys.queueRate(pid))).isPositive().isLessThanOrEqualTo(60_000L);

        assertThat(scheduler.promoteOne(pid)).isZero();
        assertThat(queueRedisStore.rate(pid)).isCloseTo(0.8 * first, within(1e-9));
    }

    @Test
    @DisplayName("한 회차가 예외를 내도 다음 회차는 승격한다")
    void oneFailingPerformanceDoesNotStopOthers() {
        long broken = openPerformance();
        long healthy = openPerformance();
        redis.opsForValue().set(RedisKeys.queueProcessing(broken), "not-a-zset");
        QueueService service = service(1);
        String first = service.enter(USER_BASE + 1, healthy).queueToken();
        String second = service.enter(USER_BASE + 2, healthy).queueToken();
        service.consume(USER_BASE + 1, healthy, first);

        scheduler(1, services.catalogOf(List.of(broken, healthy))).promoteAll();

        assertThat(service.isAdmitted(USER_BASE + 2, healthy, second)).isTrue();
    }

    @Test
    @DisplayName("승격 실행(run)은 리스를 잡았을 때만 돌고 끝나면 리스를 푼다")
    void promoteRunsUnderLease() {
        long pid = openPerformance();
        QueueService service = service(1);
        QueuePromotionScheduler scheduler = scheduler(1, services.catalogOf(List.of(pid)));
        String first = service.enter(USER_BASE + 1, pid).queueToken();
        String second = service.enter(USER_BASE + 2, pid).queueToken();
        service.consume(USER_BASE + 1, pid, first);

        redis.opsForValue().set(LEASE_KEY, "other-instance", Duration.ofSeconds(5));
        scheduler.run();
        assertThat(service.isAdmitted(USER_BASE + 2, pid, second)).isFalse();

        redis.delete(LEASE_KEY);
        scheduler.run();
        assertThat(service.isAdmitted(USER_BASE + 2, pid, second)).isTrue();
        assertThat(redis.hasKey(LEASE_KEY)).isFalse();
    }

    @Test
    @DisplayName("생존 신호가 끊긴 처리열 토큰은 입장 시각과 함께 정리되고 그 자리로 대기자가 올라간다")
    void lapsedHeartbeatFreesSlotWithAdmittedAt() {
        long pid = openPerformance();
        QueueService service = service(2);
        QueuePromotionScheduler scheduler = scheduler(2, seatCatalog);
        long idle = properties.queue().processingIdleTimeout().toMillis();
        String lapsed = service.enter(USER_BASE + 1, pid).queueToken();
        String alive = service.enter(USER_BASE + 2, pid).queueToken();
        String next = service.enter(USER_BASE + 3, pid).queueToken();
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), lapsed, System.currentTimeMillis() - 1);

        long before = System.currentTimeMillis();
        assertThat(scheduler.promoteOne(pid)).isEqualTo(1);
        long after = System.currentTimeMillis();

        assertThat(admittedAt(pid, lapsed)).isNull();
        assertThat(admittedAt(pid, alive)).isNotNull();
        double nextAdmittedAt = admittedAt(pid, next);
        assertThat(nextAdmittedAt).isBetween((double) before, (double) after);
        assertThat(queueRedisStore.processingScore(pid, next)).isEqualTo(nextAdmittedAt + idle);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueAdmitted(pid))).isEqualTo(2);
    }

    @Test
    @DisplayName("처리열에 짝이 없는 입장 시각은 최대 세션 길이가 지나면 정리된다")
    void orphanAdmittedAtIsPurgedAfterMaxSession() {
        long pid = openPerformance();
        QueueService service = service(2);
        QueuePromotionScheduler scheduler = scheduler(2, seatCatalog);
        long maxSession = properties.queue().processingMaxSession().toMillis();
        String alive = service.enter(USER_BASE + 1, pid).queueToken();
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(RedisKeys.queueAdmitted(pid), "orphan.old", now - maxSession - 1);

        scheduler.promoteOne(pid);

        assertThat(admittedAt(pid, "orphan.old")).isNull();
        assertThat(admittedAt(pid, alive)).isNotNull();
        assertThat(service.isAdmitted(USER_BASE + 1, pid, alive)).isTrue();
    }

    @Test
    @DisplayName("정원 200이 찬 뒤 30명이 신호를 끊으면 대기자 30명이 올라오고 정원은 넘지 않는다")
    void lapsedHeartbeatsAdmitSameNumberWithinCapacity() {
        long pid = openPerformance();
        int capacity = properties.queue().capacity();
        assertThat(capacity).isEqualTo(200);
        QueueService service = service(capacity);
        QueuePromotionScheduler scheduler = scheduler(capacity, seatCatalog);
        List<String> admitted = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            admitted.add(service.enter(USER_BASE + i, pid).queueToken());
        }
        List<String> waiting = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            waiting.add(service.enter(USER_BASE + 1_000 + i, pid).queueToken());
        }
        long past = System.currentTimeMillis() - 1;
        for (int i = 0; i < 30; i++) {
            redis.opsForZSet().add(RedisKeys.queueProcessing(pid), admitted.get(i), past);
        }

        assertThat(scheduler.promoteOne(pid)).isEqualTo(30);

        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(capacity);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueAdmitted(pid))).isEqualTo(capacity);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(20);
        for (int i = 0; i < 30; i++) {
            assertThat(service.isAdmitted(USER_BASE + i, pid, admitted.get(i))).isFalse();
            assertThat(service.isAdmitted(USER_BASE + 1_000 + i, pid, waiting.get(i))).isTrue();
        }
        assertThat(service.status(USER_BASE + 1_030, pid, waiting.get(30)).position()).isEqualTo(1);
        assertThat(scheduler.promoteOne(pid)).isZero();
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(capacity);
    }

    private Double admittedAt(long pid, String token) {
        return redis.opsForZSet().score(RedisKeys.queueAdmitted(pid), token);
    }

    private QueueService service(int capacity) {
        return services.queueService().properties(withCapacity(capacity)).build();
    }

    private QueuePromotionScheduler scheduler(int capacity, SeatCatalog catalog) {
        return services.queuePromotionScheduler(withCapacity(capacity), catalog);
    }

    private TicketingProperties withCapacity(int capacity) {
        return TestProperties.from(properties).queueCapacity(capacity).build();
    }

    private long openPerformance() {
        return fixtures.newPerformance(1, 1, 1);
    }
}
