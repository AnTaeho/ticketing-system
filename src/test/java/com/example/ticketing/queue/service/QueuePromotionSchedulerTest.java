package com.example.ticketing.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.SchedulerLease;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

// 스케줄러 빈은 테스트에서 꺼져 있으므로 정원을 바꿔 new로 만들고 대상 메서드를 직접 부른다
@IntegrationTest
class QueuePromotionSchedulerTest {

    private static final long USER_BASE = 7_100_000_000L;
    private static final String LEASE_KEY = "sched:lease:" + QueuePromotionScheduler.LEASE;

    @Autowired
    private QueueRedisStore repository;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private DbClock dbClock;
    @Autowired
    private SchedulerLease lease;
    @Autowired
    private TicketingProperties props;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private RedisKeyCleaner cleaner;
    @Autowired
    private TicketingRedis bookingRedis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> pids = new ArrayList<>();

    @AfterEach
    void tearDown() {
        pids.forEach(cleaner::clean);
        if ("other-instance".equals(redis().opsForValue().get(LEASE_KEY))) {
            redis().delete(LEASE_KEY);
        }
    }

    @Test
    @DisplayName("처리열에서 3명을 소모하면 waiting 앞의 3명이 도착 순서대로 승격된다")
    void promotesFifo() {
        long pid = openPerformance();
        QueueService service = service(3);
        QueuePromotionScheduler scheduler = scheduler(3, seatCatalog);
        List<String> admitted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            admitted.add(service.enter(pid, USER_BASE + i).token());
        }
        // 사용자 ID를 거꾸로 줘 같은 ms 진입에서 member 사전순과 도착 순서가 갈리게 한다
        List<Long> waitingUsers = List.of(USER_BASE + 900, USER_BASE + 800, USER_BASE + 700,
                USER_BASE + 600, USER_BASE + 500);
        List<String> waiting = new ArrayList<>();
        for (long userId : waitingUsers) {
            waiting.add(service.enter(pid, userId).token());
        }
        assertThat(scheduler.promoteOne(pid)).isZero();

        for (int i = 0; i < 3; i++) {
            assertThat(service.consume(pid, admitted.get(i), USER_BASE + i)).isTrue();
        }
        assertThat(scheduler.promoteOne(pid)).isEqualTo(3);

        for (int i = 0; i < 3; i++) {
            assertThat(service.isAdmitted(pid, waiting.get(i), waitingUsers.get(i))).isTrue();
        }
        assertThat(service.status(pid, waitingUsers.get(3), waiting.get(3)).position()).isEqualTo(1);
        assertThat(service.status(pid, waitingUsers.get(4), waiting.get(4)).position()).isEqualTo(2);
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(3);
        assertThat(scheduler.promoteOne(pid)).isZero();
    }

    @Test
    @DisplayName("처리열 점수가 지난 토큰은 정리 전에도 isAdmitted=false, 정리 후 빈 자리로 다음 사람이 승격된다")
    void expiredProcessingFreesSlot() {
        long pid = openPerformance();
        QueueService service = service(2);
        QueuePromotionScheduler scheduler = scheduler(2, seatCatalog);
        String expired = service.enter(pid, USER_BASE + 1).token();
        String alive = service.enter(pid, USER_BASE + 2).token();
        String next = service.enter(pid, USER_BASE + 3).token();
        redis().opsForZSet().add(RedisKeys.queueProcessing(pid), expired, System.currentTimeMillis() - 1);

        assertThat(service.isAdmitted(pid, expired, USER_BASE + 1)).isFalse();
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(2);

        assertThat(scheduler.promoteOne(pid)).isEqualTo(1);
        assertThat(repository.processingScore(pid, expired)).isNull();
        assertThat(service.isAdmitted(pid, alive, USER_BASE + 2)).isTrue();
        assertThat(service.isAdmitted(pid, next, USER_BASE + 3)).isTrue();
    }

    @Test
    @DisplayName("waiting 수명이 지난 토큰은 정리되고 그 뒤 사람이 승격된다")
    void expiredWaitingIsPurgedBeforePromotion() {
        long pid = openPerformance();
        QueueService service = service(1);
        QueuePromotionScheduler scheduler = scheduler(1, seatCatalog);
        String first = service.enter(pid, USER_BASE + 1).token();
        String stale = service.enter(pid, USER_BASE + 2).token();
        String fresh = service.enter(pid, USER_BASE + 3).token();
        redis().opsForZSet().add(RedisKeys.queueWaiting(pid), stale,
                System.currentTimeMillis() - props.queue().waitingTokenTtl().toMillis() - 1);
        service.consume(pid, first, USER_BASE + 1);

        assertThat(scheduler.promoteOne(pid)).isEqualTo(1);
        assertThat(repository.waitingScore(pid, stale)).isNull();
        assertThat(repository.processingScore(pid, stale)).isNull();
        assertThat(service.isAdmitted(pid, fresh, USER_BASE + 3)).isTrue();
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isZero();
    }

    @Test
    @DisplayName("rate = 0.8·이전 + 0.2·승격 수/주기초, TTL 60초")
    void updatesRate() {
        long pid = openPerformance();
        QueueService service = service(3);
        QueuePromotionScheduler scheduler = scheduler(3, seatCatalog);
        List<String> admitted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            admitted.add(service.enter(pid, USER_BASE + i).token());
        }
        for (int i = 3; i < 6; i++) {
            service.enter(pid, USER_BASE + i);
        }
        for (int i = 0; i < 3; i++) {
            service.consume(pid, admitted.get(i), USER_BASE + i);
        }
        double interval = props.queue().promoteIntervalMs() / 1000.0;

        assertThat(scheduler.promoteOne(pid)).isEqualTo(3);
        double first = 0.2 * 3 / interval;
        assertThat(repository.rate(pid)).isCloseTo(first, within(1e-9));
        Long ttl = redis().getExpire(RedisKeys.queueRate(pid), TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(60_000L);

        assertThat(scheduler.promoteOne(pid)).isZero();
        assertThat(repository.rate(pid)).isCloseTo(0.8 * first, within(1e-9));
    }

    @Test
    @DisplayName("한 회차가 예외를 내도 다음 회차는 승격한다")
    void oneFailingPerformanceDoesNotStopOthers() {
        long broken = openPerformance();
        long healthy = openPerformance();
        redis().opsForValue().set(RedisKeys.queueProcessing(broken), "not-a-zset");
        QueueService service = service(1);
        String first = service.enter(healthy, USER_BASE + 1).token();
        String second = service.enter(healthy, USER_BASE + 2).token();
        service.consume(healthy, first, USER_BASE + 1);

        scheduler(1, catalogOf(List.of(broken, healthy))).promoteAll();

        assertThat(service.isAdmitted(healthy, second, USER_BASE + 2)).isTrue();
    }

    @Test
    @DisplayName("promote()는 리스를 잡았을 때만 돈다. 남이 잡고 있으면 건너뛰고, 끝나면 리스를 푼다")
    void promoteRunsUnderLease() {
        long pid = openPerformance();
        QueueService service = service(1);
        QueuePromotionScheduler scheduler = scheduler(1, catalogOf(List.of(pid)));
        String first = service.enter(pid, USER_BASE + 1).token();
        String second = service.enter(pid, USER_BASE + 2).token();
        service.consume(pid, first, USER_BASE + 1);

        redis().opsForValue().set(LEASE_KEY, "other-instance", java.time.Duration.ofSeconds(5));
        scheduler.promote();
        assertThat(service.isAdmitted(pid, second, USER_BASE + 2)).isFalse();

        redis().delete(LEASE_KEY);
        scheduler.promote();
        assertThat(service.isAdmitted(pid, second, USER_BASE + 2)).isTrue();
        assertThat(redis().hasKey(LEASE_KEY)).isFalse();
    }

    private QueueService service(int capacity) {
        return new QueueService(repository, seatCatalog, dbClock, QueueTestProps.withCapacity(props, capacity));
    }

    private QueuePromotionScheduler scheduler(int capacity, SeatCatalog catalog) {
        return new QueuePromotionScheduler(lease, repository, catalog, QueueTestProps.withCapacity(props, capacity));
    }

    // 운영 SeatCatalog는 예매 중 회차 목록을 10초 묶어 두므로 방금 만든 회차가 빠질 수 있다
    private SeatCatalog catalogOf(List<Long> openIds) {
        return new SeatCatalog(jdbcTemplate, props) {
            @Override
            public List<Long> openPerformanceIds() {
                return openIds;
            }
        };
    }

    private long openPerformance() {
        long pid = fixtures.newPerformance(1, 1, 1);
        pids.add(pid);
        return pid;
    }

    private StringRedisTemplate redis() {
        return bookingRedis.template();
    }
}
