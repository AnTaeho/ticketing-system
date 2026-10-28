package com.example.ticketing.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.common.time.TestDbClock;
import com.example.ticketing.queue.domain.QueueTokenStatus;
import com.example.ticketing.queue.dto.QueueEntered;
import com.example.ticketing.queue.dto.QueuePosition;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class QueueServiceTest {

    // 대기열은 사용자 행을 읽지 않으므로 DB에 없는 큰 ID를 쓴다
    private static final long USER_BASE = 7_000_000_000L;

    @Autowired
    private QueueService queueService;
    @Autowired
    private QueueRedisStore repository;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private DbClock dbClock;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties props;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private RedisKeyCleaner cleaner;
    @Autowired
    private TicketingRedis bookingRedis;

    private final List<Long> pids = new ArrayList<>();

    @AfterEach
    void tearDown() {
        pids.forEach(cleaner::clean);
    }

    @Test
    @DisplayName("500명 동시 진입(정원 200): 처리열 정확히 200, 나머지 300은 WAITING, 토큰은 사람마다 하나")
    void fiveHundredEnterAtOnce() throws InterruptedException {
        int capacity = props.queue().capacity();
        assertThat(capacity).isEqualTo(200);
        long pid = openPerformance();
        int threads = 500;
        Map<Long, QueueEntered> results = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            long userId = USER_BASE + i;
            pool.submit(() -> {
                try {
                    start.await();
                    results.put(userId, queueService.enter(pid, userId));
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        long processing = results.values().stream().filter(r -> r.status() == QueueTokenStatus.PROCESSING).count();
        long waiting = results.values().stream().filter(r -> r.status() == QueueTokenStatus.WAITING).count();
        System.out.printf("[S6.1] 500명 동시 진입: PROCESSING=%d, WAITING=%d, 오류=%d%n", processing, waiting, errors.get());
        assertThat(errors.get()).isZero();
        assertThat(results).hasSize(threads);
        assertThat(processing).isEqualTo(capacity);
        assertThat(waiting).isEqualTo(threads - capacity);
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(capacity);
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(threads - capacity);
        assertThat(results.values().stream().map(QueueEntered::token).distinct().count()).isEqualTo(threads);
        results.forEach((userId, r) -> assertThat(r.token()).startsWith(userId + "."));

        Set<Long> positions = results.entrySet().stream()
                .filter(e -> e.getValue().status() == QueueTokenStatus.WAITING)
                .map(e -> queueService.status(pid, e.getKey(), e.getValue().token()).position())
                .collect(Collectors.toSet());
        assertThat(positions).isEqualTo(LongStream.rangeClosed(1, threads - capacity).boxed().collect(Collectors.toSet()));

        results.forEach((userId, r) -> {
            QueueEntered again = queueService.enter(pid, userId);
            assertThat(again).isEqualTo(r);
        });
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(capacity);
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(threads - capacity);
    }

    @Test
    @DisplayName("같은 사용자가 동시에 10번 진입해도 토큰은 하나다")
    void sameUserConcurrentEntersGetOneToken() throws InterruptedException {
        long pid = openPerformance();
        long userId = USER_BASE + 1;
        Set<String> tokens = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    tokens.add(queueService.enter(pid, userId).token());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(tokens).hasSize(1);
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))
                + redis().opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(1);
        assertThat(redis().opsForValue().get(RedisKeys.queueUser(pid, userId))).isEqualTo(tokens.iterator().next());
    }

    @Test
    @DisplayName("다시 진입하면 PROCESSING이든 WAITING이든 같은 토큰과 상태를 돌려준다")
    void reEnterReturnsSameToken() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        QueueEntered first = service.enter(pid, USER_BASE + 1);
        QueueEntered second = service.enter(pid, USER_BASE + 2);
        assertThat(first.status()).isEqualTo(QueueTokenStatus.PROCESSING);
        assertThat(second.status()).isEqualTo(QueueTokenStatus.WAITING);

        assertThat(service.enter(pid, USER_BASE + 1)).isEqualTo(first);
        assertThat(service.enter(pid, USER_BASE + 2)).isEqualTo(second);
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(1);
        Long ttl = redis().getExpire(RedisKeys.queueUser(pid, USER_BASE + 2), TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(props.queue().waitingTokenTtl().toMillis());
    }

    @Test
    @DisplayName("옛 토큰이 만료됐으면 정리 전이라도 새 토큰을 받고, 옛 토큰은 열에서 빠진다")
    void expiredTokenIsReplacedOnReEnter() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        long processingUser = USER_BASE + 1;
        long waitingUser = USER_BASE + 2;
        QueueEntered oldProcessing = service.enter(pid, processingUser);
        QueueEntered oldWaiting = service.enter(pid, waitingUser);
        long now = System.currentTimeMillis();
        redis().opsForZSet().add(RedisKeys.queueProcessing(pid), oldProcessing.token(), now - 1);
        redis().opsForZSet().add(RedisKeys.queueWaiting(pid), oldWaiting.token(),
                now - props.queue().waitingTokenTtl().toMillis() - 1);
        assertThat(service.status(pid, processingUser, oldProcessing.token()).status())
                .isEqualTo(QueueTokenStatus.EXPIRED);
        assertThat(service.status(pid, waitingUser, oldWaiting.token()).status())
                .isEqualTo(QueueTokenStatus.EXPIRED);

        // 정리 전의 만료 처리열 토큰도 정원을 차지하므로 새 토큰은 대기열 뒤에 선다
        QueueEntered renewedWaiting = service.enter(pid, waitingUser);
        assertThat(renewedWaiting.token()).isNotEqualTo(oldWaiting.token());
        assertThat(renewedWaiting.status()).isEqualTo(QueueTokenStatus.WAITING);
        assertThat(repository.waitingScore(pid, oldWaiting.token())).isNull();

        QueueEntered renewedProcessing = service.enter(pid, processingUser);
        assertThat(renewedProcessing.token()).isNotEqualTo(oldProcessing.token());
        assertThat(renewedProcessing.status()).isEqualTo(QueueTokenStatus.WAITING);
        assertThat(repository.processingScore(pid, oldProcessing.token())).isNull();
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isZero();
        assertThat(service.status(pid, waitingUser, renewedWaiting.token()).position()).isEqualTo(1);
        assertThat(service.status(pid, processingUser, renewedProcessing.token()).position()).isEqualTo(2);
    }

    @Test
    @DisplayName("예매 전·예매가 끝난 회차는 409 NOT_OPEN이고 대기열 키를 만들지 않는다")
    void notOpen() {
        LocalDateTime now = fixtures.dbNow();
        long show = fixtures.newShow();
        long before = track(fixtures.newPerformance(show, now.plusHours(1), now.plusDays(1), 1, 1, 1));
        long closed = track(fixtures.newPerformance(show, now.minusDays(2), now.minusDays(1), 1, 1, 1));

        for (long pid : List.of(before, closed)) {
            assertThatThrownBy(() -> queueService.enter(pid, USER_BASE + 1))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCode.NOT_OPEN);
            assertThat(cleaner.keys(pid)).isEmpty();
        }
    }

    @Test
    @DisplayName("오픈 판정은 DB 시각 기준: DB가 서버보다 2시간 앞서면 열린 회차, 2시간 뒤지면 오픈 전(409)")
    void openCheckUsesDbTime() {
        LocalDateTime now = fixtures.dbNow();
        long show = fixtures.newShow();
        long opensInAnHour = track(fixtures.newPerformance(show, now.plusHours(1), now.plusDays(1), 1, 1, 1));
        long openedAnHourAgo = track(fixtures.newPerformance(show, now.minusHours(1), now.plusDays(1), 1, 1, 1));
        QueueService dbAhead = new QueueService(repository, seatCatalog,
                TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(2)), props);
        QueueService dbBehind = new QueueService(repository, seatCatalog,
                TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(-2)), props);

        assertThat(dbAhead.enter(opensInAnHour, USER_BASE + 1).status()).isEqualTo(QueueTokenStatus.PROCESSING);
        assertThatThrownBy(() -> dbBehind.enter(openedAnHourAgo, USER_BASE + 2))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.NOT_OPEN);
        assertThat(cleaner.keys(openedAnHourAgo)).isEmpty();
    }

    @Test
    @DisplayName("isAdmitted: 내 처리열 토큰만 true. 남의 토큰·없는 토큰·대기 토큰·형식이 틀린 토큰·만료 점수는 false")
    void isAdmitted() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        long owner = USER_BASE + 1;
        long other = USER_BASE + 2;
        String admitted = service.enter(pid, owner).token();
        String waiting = service.enter(pid, other).token();

        assertThat(service.isAdmitted(pid, admitted, owner)).isTrue();
        assertThat(service.isAdmitted(pid, admitted, other)).isFalse();
        assertThat(service.isAdmitted(pid, owner + ".no-such-token", owner)).isFalse();
        assertThat(service.isAdmitted(pid, waiting, other)).isFalse();
        assertThat(service.isAdmitted(pid, "garbage", owner)).isFalse();
        assertThat(service.isAdmitted(pid, null, owner)).isFalse();

        redis().opsForZSet().add(RedisKeys.queueProcessing(pid), admitted, System.currentTimeMillis() - 1);
        assertThat(service.isAdmitted(pid, admitted, owner)).isFalse();
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(1);
    }

    @Test
    @DisplayName("status: 순번은 1부터, 예상 시간은 ceil(순번 / max(rate, 0.1)). 처리열은 0, 남의 토큰은 404")
    void statusPositionAndEstimate() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        String processing = service.enter(pid, USER_BASE).token();
        List<String> waiting = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            waiting.add(service.enter(pid, USER_BASE + i).token());
        }

        QueuePosition third = service.status(pid, USER_BASE + 3, waiting.get(2));
        assertThat(third).isEqualTo(QueuePosition.waiting(3, 30));

        repository.saveRate(pid, 2.0, QueuePromotionScheduler.RATE_TTL);
        assertThat(service.status(pid, USER_BASE + 5, waiting.get(4))).isEqualTo(QueuePosition.waiting(5, 3));
        assertThat(service.status(pid, USER_BASE + 1, waiting.get(0))).isEqualTo(QueuePosition.waiting(1, 1));
        assertThat(service.status(pid, USER_BASE, processing)).isEqualTo(QueuePosition.processing());
        assertThat(service.status(pid, USER_BASE, USER_BASE + ".unknown")).isEqualTo(QueuePosition.expired());

        assertThatThrownBy(() -> service.status(pid, USER_BASE + 9, processing))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
    }

    @Test
    @DisplayName("consume: 처리열과 사용자 키를 지운다. 남의 토큰으로는 지우지 못한다. 소모 뒤 재진입은 새 토큰")
    void consume() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        String token = queueService.enter(pid, owner).token();

        assertThat(queueService.consume(pid, token, USER_BASE + 2)).isFalse();
        assertThat(queueService.isAdmitted(pid, token, owner)).isTrue();

        assertThat(queueService.consume(pid, token, owner)).isTrue();
        assertThat(queueService.isAdmitted(pid, token, owner)).isFalse();
        assertThat(redis().hasKey(RedisKeys.queueUser(pid, owner))).isFalse();
        assertThat(queueService.consume(pid, token, owner)).isFalse();

        QueueEntered again = queueService.enter(pid, owner);
        assertThat(again.token()).isNotEqualTo(token);
        assertThat(again.status()).isEqualTo(QueueTokenStatus.PROCESSING);
    }

    @Test
    @DisplayName("consume은 그 사이 새로 받은 토큰의 사용자 키를 지우지 않는다")
    void consumeKeepsNewerUserKey() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        String old = queueService.enter(pid, owner).token();
        redis().opsForZSet().add(RedisKeys.queueProcessing(pid), old, System.currentTimeMillis() - 1);
        String renewed = queueService.enter(pid, owner).token();
        assertThat(renewed).isNotEqualTo(old);

        queueService.consume(pid, old, owner);

        assertThat(redis().opsForValue().get(RedisKeys.queueUser(pid, owner))).isEqualTo(renewed);
        assertThat(queueService.isAdmitted(pid, renewed, owner)).isTrue();
    }

    private QueueService serviceWithCapacity(int capacity) {
        return new QueueService(repository, seatCatalog, dbClock, QueueTestProps.withCapacity(props, capacity));
    }

    private long openPerformance() {
        return track(fixtures.newPerformance(1, 1, 1));
    }

    private long track(long pid) {
        pids.add(pid);
        return pid;
    }

    private StringRedisTemplate redis() {
        return bookingRedis.template();
    }
}
