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
import com.example.ticketing.queue.dto.QueueEnterResult;
import com.example.ticketing.queue.dto.QueueStatusResult;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
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
    private MeterRegistry meterRegistry;
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
        Map<Long, QueueEnterResult> results = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            long userId = USER_BASE + i;
            pool.submit(() -> {
                try {
                    start.await();
                    results.put(userId, queueService.enter(userId, pid));
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
        assertThat(results.values().stream().map(QueueEnterResult::queueToken).distinct().count()).isEqualTo(threads);
        results.forEach((userId, r) -> assertThat(r.queueToken()).startsWith(userId + "."));

        Set<Long> positions = results.entrySet().stream()
                .filter(e -> e.getValue().status() == QueueTokenStatus.WAITING)
                .map(e -> queueService.status(e.getKey(), pid, e.getValue().queueToken()).position())
                .collect(Collectors.toSet());
        assertThat(positions).isEqualTo(LongStream.rangeClosed(1, threads - capacity).boxed().collect(Collectors.toSet()));

        results.forEach((userId, r) -> {
            QueueEnterResult again = queueService.enter(userId, pid);
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
                    tokens.add(queueService.enter(userId, pid).queueToken());
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
        assertThat(redis().opsForValue().get(RedisKeys.queueUser(userId, pid))).isEqualTo(tokens.iterator().next());
    }

    @Test
    @DisplayName("다시 진입하면 PROCESSING이든 WAITING이든 같은 토큰과 상태를 돌려준다")
    void reEnterReturnsSameToken() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        QueueEnterResult first = service.enter(USER_BASE + 1, pid);
        QueueEnterResult second = service.enter(USER_BASE + 2, pid);
        assertThat(first.status()).isEqualTo(QueueTokenStatus.PROCESSING);
        assertThat(second.status()).isEqualTo(QueueTokenStatus.WAITING);

        assertThat(service.enter(USER_BASE + 1, pid)).isEqualTo(first);
        assertThat(service.enter(USER_BASE + 2, pid)).isEqualTo(second);
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(1);
        Long ttl = redis().getExpire(RedisKeys.queueUser(USER_BASE + 2, pid), TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(props.queue().waitingTokenTtl().toMillis());
    }

    @Test
    @DisplayName("옛 토큰이 만료됐으면 정리 전이라도 새 토큰을 받고, 옛 토큰은 열에서 빠진다")
    void expiredTokenIsReplacedOnReEnter() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        long processingUser = USER_BASE + 1;
        long waitingUser = USER_BASE + 2;
        QueueEnterResult oldProcessing = service.enter(processingUser, pid);
        QueueEnterResult oldWaiting = service.enter(waitingUser, pid);
        long now = System.currentTimeMillis();
        redis().opsForZSet().add(RedisKeys.queueProcessing(pid), oldProcessing.queueToken(), now - 1);
        redis().opsForZSet().add(RedisKeys.queueWaiting(pid), oldWaiting.queueToken(),
                now - props.queue().waitingTokenTtl().toMillis() - 1);
        assertThat(service.status(processingUser, pid, oldProcessing.queueToken()).status())
                .isEqualTo(QueueTokenStatus.EXPIRED);
        assertThat(service.status(waitingUser, pid, oldWaiting.queueToken()).status())
                .isEqualTo(QueueTokenStatus.EXPIRED);

        // 정리 전의 만료 처리열 토큰도 정원을 차지하므로 새 토큰은 대기열 뒤에 선다
        QueueEnterResult renewedWaiting = service.enter(waitingUser, pid);
        assertThat(renewedWaiting.queueToken()).isNotEqualTo(oldWaiting.queueToken());
        assertThat(renewedWaiting.status()).isEqualTo(QueueTokenStatus.WAITING);
        assertThat(repository.waitingScore(pid, oldWaiting.queueToken())).isNull();

        QueueEnterResult renewedProcessing = service.enter(processingUser, pid);
        assertThat(renewedProcessing.queueToken()).isNotEqualTo(oldProcessing.queueToken());
        assertThat(renewedProcessing.status()).isEqualTo(QueueTokenStatus.WAITING);
        assertThat(repository.processingScore(pid, oldProcessing.queueToken())).isNull();
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isZero();
        assertThat(service.status(waitingUser, pid, renewedWaiting.queueToken()).position()).isEqualTo(1);
        assertThat(service.status(processingUser, pid, renewedProcessing.queueToken()).position()).isEqualTo(2);
    }

    @Test
    @DisplayName("예매 전·예매가 끝난 회차는 409 NOT_OPEN이고 대기열 키를 만들지 않는다")
    void notOpen() {
        LocalDateTime now = fixtures.dbNow();
        long show = fixtures.newShow();
        long before = track(fixtures.newPerformance(show, now.plusHours(1), now.plusDays(1), 1, 1, 1));
        long closed = track(fixtures.newPerformance(show, now.minusDays(2), now.minusDays(1), 1, 1, 1));

        for (long pid : List.of(before, closed)) {
            assertThatThrownBy(() -> queueService.enter(USER_BASE + 1, pid))
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
                TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(2)), Clock.systemDefaultZone(), System::nanoTime,
                props, meterRegistry);
        QueueService dbBehind = new QueueService(repository, seatCatalog,
                TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(-2)), Clock.systemDefaultZone(), System::nanoTime,
                props, meterRegistry);

        assertThat(dbAhead.enter(USER_BASE + 1, opensInAnHour).status()).isEqualTo(QueueTokenStatus.PROCESSING);
        assertThatThrownBy(() -> dbBehind.enter(USER_BASE + 2, openedAnHourAgo))
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
        String admitted = service.enter(owner, pid).queueToken();
        String waiting = service.enter(other, pid).queueToken();

        assertThat(service.isAdmitted(owner, pid, admitted)).isTrue();
        assertThat(service.isAdmitted(other, pid, admitted)).isFalse();
        assertThat(service.isAdmitted(owner, pid, owner + ".no-such-token")).isFalse();
        assertThat(service.isAdmitted(other, pid, waiting)).isFalse();
        assertThat(service.isAdmitted(owner, pid, "garbage")).isFalse();
        assertThat(service.isAdmitted(owner, pid, null)).isFalse();

        redis().opsForZSet().add(RedisKeys.queueProcessing(pid), admitted, System.currentTimeMillis() - 1);
        assertThat(service.isAdmitted(owner, pid, admitted)).isFalse();
        assertThat(redis().opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(1);
    }

    @Test
    @DisplayName("status: 순번은 1부터, 예상 시간은 ceil(순번 / max(rate, 0.1)). 처리열은 0, 남의 토큰은 404")
    void statusPositionAndEstimate() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        String processing = service.enter(USER_BASE, pid).queueToken();
        List<String> waiting = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            waiting.add(service.enter(USER_BASE + i, pid).queueToken());
        }

        QueueStatusResult third = service.status(USER_BASE + 3, pid, waiting.get(2));
        assertThat(third).isEqualTo(QueueStatusResult.waiting(3, 30));

        repository.saveRate(pid, 2.0, QueuePromotionScheduler.RATE_TTL);
        assertThat(service.status(USER_BASE + 5, pid, waiting.get(4))).isEqualTo(QueueStatusResult.waiting(5, 3));
        assertThat(service.status(USER_BASE + 1, pid, waiting.get(0))).isEqualTo(QueueStatusResult.waiting(1, 1));
        assertThat(service.status(USER_BASE, pid, processing)).isEqualTo(QueueStatusResult.processing());
        assertThat(service.status(USER_BASE, pid, USER_BASE + ".unknown")).isEqualTo(QueueStatusResult.expired());

        assertThatThrownBy(() -> service.status(USER_BASE + 9, pid, processing))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
    }

    @Test
    @DisplayName("consume: 처리열과 사용자 키를 지운다. 남의 토큰으로는 지우지 못한다. 소모 뒤 재진입은 새 토큰")
    void consume() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        String token = queueService.enter(owner, pid).queueToken();

        assertThat(queueService.consume(USER_BASE + 2, pid, token)).isFalse();
        assertThat(queueService.isAdmitted(owner, pid, token)).isTrue();

        assertThat(queueService.consume(owner, pid, token)).isTrue();
        assertThat(queueService.isAdmitted(owner, pid, token)).isFalse();
        assertThat(redis().hasKey(RedisKeys.queueUser(owner, pid))).isFalse();
        assertThat(queueService.consume(owner, pid, token)).isFalse();

        QueueEnterResult again = queueService.enter(owner, pid);
        assertThat(again.queueToken()).isNotEqualTo(token);
        assertThat(again.status()).isEqualTo(QueueTokenStatus.PROCESSING);
    }

    @Test
    @DisplayName("consume은 그 사이 새로 받은 토큰의 사용자 키를 지우지 않는다")
    void consumeKeepsNewerUserKey() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        String old = queueService.enter(owner, pid).queueToken();
        redis().opsForZSet().add(RedisKeys.queueProcessing(pid), old, System.currentTimeMillis() - 1);
        String renewed = queueService.enter(owner, pid).queueToken();
        assertThat(renewed).isNotEqualTo(old);

        queueService.consume(owner, pid, old);

        assertThat(redis().opsForValue().get(RedisKeys.queueUser(owner, pid))).isEqualTo(renewed);
        assertThat(queueService.isAdmitted(owner, pid, renewed)).isTrue();
    }

    private QueueService serviceWithCapacity(int capacity) {
        return new QueueService(repository, seatCatalog, dbClock, Clock.systemDefaultZone(), System::nanoTime,
                QueueTestProps.withCapacity(props, capacity), meterRegistry);
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
