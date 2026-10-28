package com.example.ticketing.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.time.TestDbClock;
import com.example.ticketing.queue.domain.QueueTokenStatus;
import com.example.ticketing.queue.dto.QueueEnterResult;
import com.example.ticketing.queue.dto.QueueHeartbeatResult;
import com.example.ticketing.queue.dto.QueueStatusResult;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
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
    private QueueRedisStore queueRedisStore;
    @Autowired
    private SeatMapRedisStore seatMapRedisStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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

    @Test
    @DisplayName("정원 200에 500명이 동시에 들어오면 200명만 처리열에 들어간다")
    void fiveHundredEnterAtOnce() {
        int capacity = properties.queue().capacity();
        assertThat(capacity).isEqualTo(200);
        long pid = openPerformance();
        int threads = 500;

        Results<QueueEnterResult> entered = Concurrently.of(threads)
                .threads(64)
                .within(Duration.ofSeconds(30))
                .run(i -> queueService.enter(USER_BASE + i, pid));

        assertThat(entered.errors()).isEmpty();
        Map<Long, QueueEnterResult> results = new HashMap<>();
        for (int i = 0; i < threads; i++) {
            results.put(USER_BASE + i, entered.outcomes().get(i).value());
        }
        long processing = results.values().stream().filter(r -> r.status() == QueueTokenStatus.PROCESSING).count();
        long waiting = results.values().stream().filter(r -> r.status() == QueueTokenStatus.WAITING).count();
        assertThat(results).hasSize(threads);
        assertThat(processing).isEqualTo(capacity);
        assertThat(waiting).isEqualTo(threads - capacity);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(capacity);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(threads - capacity);
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
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(capacity);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(threads - capacity);
    }

    @Test
    @DisplayName("같은 사용자가 동시에 10번 진입해도 토큰은 하나다")
    void sameUserConcurrentEntersGetOneToken() {
        long pid = openPerformance();
        long userId = USER_BASE + 1;

        Results<String> entered = Concurrently.of(10)
                .within(Duration.ofSeconds(10))
                .run(i -> queueService.enter(userId, pid).queueToken());

        assertThat(entered.errors()).isEmpty();
        Set<String> tokens = Set.copyOf(entered.values());
        assertThat(tokens).hasSize(1);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))
                + redis.opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(1);
        assertThat(redis.opsForValue().get(RedisKeys.queueUser(userId, pid))).isEqualTo(tokens.iterator().next());
    }

    @Test
    @DisplayName("다시 진입하면 처리열이든 대기열이든 같은 토큰과 상태를 돌려준다")
    void reEnterReturnsSameToken() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        QueueEnterResult first = service.enter(USER_BASE + 1, pid);
        QueueEnterResult second = service.enter(USER_BASE + 2, pid);
        assertThat(first.status()).isEqualTo(QueueTokenStatus.PROCESSING);
        assertThat(second.status()).isEqualTo(QueueTokenStatus.WAITING);

        assertThat(service.enter(USER_BASE + 1, pid)).isEqualTo(first);
        assertThat(service.enter(USER_BASE + 2, pid)).isEqualTo(second);
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(1);
        assertThat(testRedis.pttl(RedisKeys.queueUser(USER_BASE + 2, pid)))
                .isPositive().isLessThanOrEqualTo(properties.queue().waitingTokenTtl().toMillis());
    }

    @Test
    @DisplayName("옛 토큰이 만료됐으면 정리 전이라도 새 토큰을 받고 옛 토큰은 빠진다")
    void expiredTokenIsReplacedOnReEnter() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        long processingUser = USER_BASE + 1;
        long waitingUser = USER_BASE + 2;
        QueueEnterResult oldProcessing = service.enter(processingUser, pid);
        QueueEnterResult oldWaiting = service.enter(waitingUser, pid);
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), oldProcessing.queueToken(), now - 1);
        redis.opsForZSet().add(RedisKeys.queueWaiting(pid), oldWaiting.queueToken(),
                now - properties.queue().waitingTokenTtl().toMillis() - 1);
        assertThat(service.status(processingUser, pid, oldProcessing.queueToken()).status())
                .isEqualTo(QueueTokenStatus.EXPIRED);
        assertThat(service.status(waitingUser, pid, oldWaiting.queueToken()).status())
                .isEqualTo(QueueTokenStatus.EXPIRED);

        // 정리 전의 만료 처리열 토큰도 정원을 차지하므로 새 토큰은 대기열 뒤에 선다
        QueueEnterResult renewedWaiting = service.enter(waitingUser, pid);
        assertThat(renewedWaiting.queueToken()).isNotEqualTo(oldWaiting.queueToken());
        assertThat(renewedWaiting.status()).isEqualTo(QueueTokenStatus.WAITING);
        assertThat(queueRedisStore.waitingScore(pid, oldWaiting.queueToken())).isNull();

        QueueEnterResult renewedProcessing = service.enter(processingUser, pid);
        assertThat(renewedProcessing.queueToken()).isNotEqualTo(oldProcessing.queueToken());
        assertThat(renewedProcessing.status()).isEqualTo(QueueTokenStatus.WAITING);
        assertThat(queueRedisStore.processingScore(pid, oldProcessing.queueToken())).isNull();
        assertThat(admittedAt(pid, oldProcessing.queueToken())).isNull();
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isZero();
        assertThat(service.status(waitingUser, pid, renewedWaiting.queueToken()).position()).isEqualTo(1);
        assertThat(service.status(processingUser, pid, renewedProcessing.queueToken()).position()).isEqualTo(2);
    }

    @Test
    @DisplayName("예매 전이나 끝난 회차에 들어오면 409 NOT_OPEN이고 키를 만들지 않는다")
    void notOpen() {
        LocalDateTime now = fixtures.dbNow();
        long show = fixtures.newShow();
        long before = fixtures.newPerformance(show, now.plusHours(1), now.plusDays(1), 1, 1, 1);
        long closed = fixtures.newPerformance(show, now.minusDays(2), now.minusDays(1), 1, 1, 1);

        for (long pid : List.of(before, closed)) {
            assertThatThrownBy(() -> queueService.enter(USER_BASE + 1, pid))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCode.NOT_OPEN);
            assertThat(testRedis.keys(pid)).isEmpty();
        }
    }

    @Test
    @DisplayName("예매가 열렸는지는 DB 시각으로 판정한다")
    void openCheckUsesDbTime() {
        LocalDateTime now = fixtures.dbNow();
        long show = fixtures.newShow();
        long opensInAnHour = fixtures.newPerformance(show, now.plusHours(1), now.plusDays(1), 1, 1, 1);
        long openedAnHourAgo = fixtures.newPerformance(show, now.minusHours(1), now.plusDays(1), 1, 1, 1);
        QueueService dbAhead = services.queueService()
                .dbClock(TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(2)))
                .build();
        QueueService dbBehind = services.queueService()
                .dbClock(TestDbClock.withDbShift(jdbcTemplate, Duration.ofHours(-2)))
                .build();

        assertThat(dbAhead.enter(USER_BASE + 1, opensInAnHour).status()).isEqualTo(QueueTokenStatus.PROCESSING);
        assertThatThrownBy(() -> dbBehind.enter(USER_BASE + 2, openedAnHourAgo))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.NOT_OPEN);
        assertThat(testRedis.keys(openedAnHourAgo)).isEmpty();
    }

    @Test
    @DisplayName("처리열 토큰 확인(isAdmitted)은 내 살아 있는 처리열 토큰만 통과시킨다")
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

        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), admitted, System.currentTimeMillis() - 1);
        assertThat(service.isAdmitted(owner, pid, admitted)).isFalse();
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueProcessing(pid))).isEqualTo(1);
    }

    @Test
    @DisplayName("대기 상태 조회는 1부터 센 순번과 처리 속도로 셈한 예상 시간을 준다")
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

        queueRedisStore.saveRate(pid, 2.0, QueuePromotionScheduler.RATE_TTL);
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
    @DisplayName("토큰을 소모하면 처리열과 사용자 키가 지워지고 다시 들어오면 새 토큰을 받는다")
    void consume() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        String token = queueService.enter(owner, pid).queueToken();

        assertThat(queueService.consume(USER_BASE + 2, pid, token)).isFalse();
        assertThat(queueService.isAdmitted(owner, pid, token)).isTrue();
        assertThat(admittedAt(pid, token)).isNotNull();

        assertThat(queueService.consume(owner, pid, token)).isTrue();
        assertThat(queueService.isAdmitted(owner, pid, token)).isFalse();
        assertThat(redis.hasKey(RedisKeys.queueUser(owner, pid))).isFalse();
        assertThat(admittedAt(pid, token)).isNull();
        assertThat(queueService.consume(owner, pid, token)).isFalse();

        QueueEnterResult again = queueService.enter(owner, pid);
        assertThat(again.queueToken()).isNotEqualTo(token);
        assertThat(again.status()).isEqualTo(QueueTokenStatus.PROCESSING);
    }

    @Test
    @DisplayName("토큰 소모(consume)는 그 사이 새로 받은 토큰의 사용자 키를 지우지 않는다")
    void consumeKeepsNewerUserKey() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        String old = queueService.enter(owner, pid).queueToken();
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), old, System.currentTimeMillis() - 1);
        String renewed = queueService.enter(owner, pid).queueToken();
        assertThat(renewed).isNotEqualTo(old);

        queueService.consume(owner, pid, old);

        assertThat(redis.opsForValue().get(RedisKeys.queueUser(owner, pid))).isEqualTo(renewed);
        assertThat(queueService.isAdmitted(owner, pid, renewed)).isTrue();
    }

    @Test
    @DisplayName("입장하면 처리열 점수는 지금 + 무응답 한도이고 입장 시각을 따로 적는다")
    void enterScoresIdleTimeoutAndRecordsAdmittedAt() {
        long pid = openPerformance();
        long idle = properties.queue().processingIdleTimeout().toMillis();
        long maxSession = properties.queue().processingMaxSession().toMillis();
        assertThat(idle).isEqualTo(120_000L);
        assertThat(maxSession).isEqualTo(600_000L);

        long before = System.currentTimeMillis();
        String token = queueService.enter(USER_BASE + 1, pid).queueToken();
        long after = System.currentTimeMillis();

        double admittedAt = admittedAt(pid, token);
        double expireAt = queueRedisStore.processingScore(pid, token);
        assertThat(admittedAt).isBetween((double) before, (double) after);
        assertThat(expireAt).isEqualTo(admittedAt + idle);
        assertThat(expireAt).isLessThanOrEqualTo(admittedAt + maxSession);
    }

    @Test
    @DisplayName("생존 신호를 보내면 처리열 점수가 지금 + 무응답 한도로 늘어난다")
    void heartbeatExtendsScore() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        long idle = properties.queue().processingIdleTimeout().toMillis();
        String token = queueService.enter(owner, pid).queueToken();
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), token, System.currentTimeMillis() + 1_000);

        long before = System.currentTimeMillis();
        QueueHeartbeatResult result = queueService.heartbeat(owner, pid, token);
        long after = System.currentTimeMillis();

        assertThat(result.status()).isEqualTo(QueueTokenStatus.PROCESSING);
        assertThat(result.expiresInMs()).isEqualTo(idle);
        assertThat(queueRedisStore.processingScore(pid, token))
                .isBetween((double) before + idle, (double) after + idle);
        assertThat(queueService.isAdmitted(owner, pid, token)).isTrue();
    }

    @Test
    @DisplayName("생존 신호를 보내도 처리열 점수는 입장 시각 + 최대 세션 길이를 넘지 않는다")
    void heartbeatIsCappedByMaxSession() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        long maxSession = properties.queue().processingMaxSession().toMillis();
        String token = queueService.enter(owner, pid).queueToken();
        long admittedAt = System.currentTimeMillis() - maxSession + 5_000;
        redis.opsForZSet().add(RedisKeys.queueAdmitted(pid), token, admittedAt);

        QueueHeartbeatResult result = queueService.heartbeat(owner, pid, token);

        assertThat(result.expiresInMs()).isPositive().isLessThanOrEqualTo(5_000L);
        assertThat(queueRedisStore.processingScore(pid, token)).isEqualTo((double) admittedAt + maxSession);
    }

    @Test
    @DisplayName("만료됐거나 처리열에 없는 토큰의 생존 신호는 403 NOT_ADMITTED이고 남의 토큰은 404다")
    void heartbeatRejectsExpiredAndForeignTokens() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        long owner = USER_BASE + 1;
        long waiter = USER_BASE + 2;
        long maxSession = properties.queue().processingMaxSession().toMillis();
        String admitted = service.enter(owner, pid).queueToken();
        String waiting = service.enter(waiter, pid).queueToken();

        assertThatThrownBy(() -> service.heartbeat(waiter, pid, admitted))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        for (String token : new String[]{waiting, waiter + ".no-such-token"}) {
            assertThatThrownBy(() -> service.heartbeat(waiter, pid, token))
                    .as("token=%s", token)
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCode.NOT_ADMITTED);
        }

        long now = System.currentTimeMillis();
        redis.opsForZSet().add(RedisKeys.queueAdmitted(pid), admitted, now - maxSession - 1);
        assertThatThrownBy(() -> service.heartbeat(owner, pid, admitted))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.NOT_ADMITTED);

        redis.opsForZSet().add(RedisKeys.queueAdmitted(pid), admitted, now);
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), admitted, now - 1);
        assertThatThrownBy(() -> service.heartbeat(owner, pid, admitted))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.NOT_ADMITTED);
        assertThat(queueRedisStore.processingScore(pid, admitted)).isEqualTo((double) now - 1);
    }

    @Test
    @DisplayName("입장 시각이 없는 처리열 토큰은 생존 신호로 늘리지 않고 남은 시간만 돌려준다")
    void heartbeatKeepsScoreWithoutAdmittedAt() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        String token = queueService.enter(owner, pid).queueToken();
        long expireAt = System.currentTimeMillis() + 5_000;
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), token, expireAt);
        redis.opsForZSet().remove(RedisKeys.queueAdmitted(pid), token);

        QueueHeartbeatResult result = queueService.heartbeat(owner, pid, token);

        assertThat(result.expiresInMs()).isPositive().isLessThanOrEqualTo(5_000L);
        assertThat(queueRedisStore.processingScore(pid, token)).isEqualTo((double) expireAt);
    }

    @Test
    @DisplayName("떠남 신호를 받으면 처리열 점수를 지금 + 떠남 유예로 줄이고, 다시 생존 신호가 오면 무응답 한도만큼 되돌린다")
    void leaveShortensAndHeartbeatRestores() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        long idle = properties.queue().processingIdleTimeout().toMillis();
        long grace = properties.queue().processingLeaveGrace().toMillis();
        assertThat(grace).isEqualTo(10_000L);
        String token = queueService.enter(owner, pid).queueToken();

        long before = System.currentTimeMillis();
        queueService.leave(owner, pid, token);
        long after = System.currentTimeMillis();

        assertThat(queueRedisStore.processingScore(pid, token))
                .isBetween((double) before + grace, (double) after + grace);
        assertThat(queueService.isAdmitted(owner, pid, token)).isTrue();
        assertThat(admittedAt(pid, token)).isNotNull();

        QueueHeartbeatResult reopened = queueService.heartbeat(owner, pid, token);

        assertThat(reopened.expiresInMs()).isEqualTo(idle);
        assertThat(queueRedisStore.processingScore(pid, token)).isGreaterThan((double) after + grace);
    }

    @Test
    @DisplayName("남은 시간이 떠남 유예보다 짧으면 떠남 신호가 점수를 바꾸지 않는다")
    void leaveKeepsShorterScore() {
        long pid = openPerformance();
        long owner = USER_BASE + 1;
        String token = queueService.enter(owner, pid).queueToken();
        long expireAt = System.currentTimeMillis() + 3_000;
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), token, expireAt);

        queueService.leave(owner, pid, token);

        assertThat(queueRedisStore.processingScore(pid, token)).isEqualTo((double) expireAt);
    }

    @Test
    @DisplayName("소모·만료된 토큰의 떠남 신호는 아무것도 바꾸지 않고, 대기 중인 토큰은 순번을 둔 채 떠남만 적고, 남의 토큰은 404다")
    void leaveIgnoresConsumedExpiredAndMarksWaitingTokens() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(2);
        long consumedUser = USER_BASE + 1;
        long expiredUser = USER_BASE + 2;
        long waiter = USER_BASE + 3;
        String consumed = service.enter(consumedUser, pid).queueToken();
        String expired = service.enter(expiredUser, pid).queueToken();
        String waiting = service.enter(waiter, pid).queueToken();
        service.consume(consumedUser, pid, consumed);
        long past = System.currentTimeMillis() - 1;
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), expired, past);
        Double waitingScore = queueRedisStore.waitingScore(pid, waiting);

        service.leave(consumedUser, pid, consumed);
        service.leave(expiredUser, pid, expired);
        service.leave(waiter, pid, waiting);

        assertThat(queueRedisStore.processingScore(pid, consumed)).isNull();
        assertThat(queueRedisStore.processingScore(pid, expired)).isEqualTo((double) past);
        assertThat(queueRedisStore.processingScore(pid, waiting)).isNull();
        assertThat(queueRedisStore.waitingScore(pid, waiting)).isEqualTo(waitingScore);
        assertThat(leaveDeadline(pid, waiting)).isNotNull();
        assertThat(leaveDeadline(pid, consumed)).isNull();
        assertThat(leaveDeadline(pid, expired)).isNull();
        assertThat(service.status(waiter, pid, waiting).position()).isEqualTo(1);
        assertThatThrownBy(() -> service.leave(waiter, pid, expired))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        assertThat(queueRedisStore.processingScore(pid, expired)).isEqualTo((double) past);
    }

    @Test
    @DisplayName("대기 중에 떠나면 지금 + 대기 떠남 유예에 줄에서 빼도록 적고, 여러 번 와도 처음 시각을 지킨다")
    void waitingLeaveRecordsDeadlineOnce() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        long waiter = USER_BASE + 2;
        long grace = properties.queue().waitingLeaveGrace().toMillis();
        assertThat(grace).isEqualTo(10_000L);
        service.enter(USER_BASE + 1, pid);
        String waiting = service.enter(waiter, pid).queueToken();

        long before = System.currentTimeMillis();
        service.leave(waiter, pid, waiting);
        long after = System.currentTimeMillis();
        double first = leaveDeadline(pid, waiting);
        service.leave(waiter, pid, waiting);

        assertThat(first).isBetween((double) before + grace, (double) after + grace);
        assertThat(leaveDeadline(pid, waiting)).isEqualTo(first);
        assertThat(redis.opsForValue().get(RedisKeys.queueUser(waiter, pid))).isEqualTo(waiting);
    }

    @Test
    @DisplayName("떠난 뒤 유예 안에 순서를 물으면(새로고침) 떠남 기록이 지워지고 순번이 그대로다")
    void statusWithinGraceKeepsPlace() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        service.enter(USER_BASE, pid);
        String first = service.enter(USER_BASE + 1, pid).queueToken();
        String second = service.enter(USER_BASE + 2, pid).queueToken();
        service.leave(USER_BASE + 1, pid, first);

        QueueStatusResult again = service.status(USER_BASE + 1, pid, first);

        assertThat(again.status()).isEqualTo(QueueTokenStatus.WAITING);
        assertThat(again.position()).isEqualTo(1);
        assertThat(leaveDeadline(pid, first)).isNull();
        assertThat(service.status(USER_BASE + 2, pid, second).position()).isEqualTo(2);
    }

    @Test
    @DisplayName("떠난 뒤 유예 안에 다시 진입하면 같은 토큰과 순번을 받고 떠남 기록이 지워진다")
    void reEnterWithinGraceKeepsToken() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        service.enter(USER_BASE, pid);
        QueueEnterResult waiting = service.enter(USER_BASE + 1, pid);
        service.leave(USER_BASE + 1, pid, waiting.queueToken());

        QueueEnterResult again = service.enter(USER_BASE + 1, pid);

        assertThat(again).isEqualTo(waiting);
        assertThat(leaveDeadline(pid, waiting.queueToken())).isNull();
        assertThat(service.status(USER_BASE + 1, pid, waiting.queueToken()).position()).isEqualTo(1);
    }

    @Test
    @DisplayName("매진이면 진입은 409 SOLD_OUT이고 대기열 키를 만들지 않는다")
    void enterRejectedWhenSoldOut() {
        long pid = openPerformance();
        markSoldOut(pid, true);

        assertThatThrownBy(() -> queueService.enter(USER_BASE + 1, pid))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SOLD_OUT);
        assertThat(testRedis.keys(pid)).noneMatch(key -> key.startsWith("bq:"));

        markSoldOut(pid, false);
        assertThat(queueService.enter(USER_BASE + 1, pid).status()).isEqualTo(QueueTokenStatus.PROCESSING);
    }

    @Test
    @DisplayName("매진 표시가 없으면 매진이 아닌 것으로 보고 들여보낸다")
    void missingSoldOutFlagAdmits() {
        long pid = openPerformance();
        assertThat(redis.hasKey(RedisKeys.seatMapSoldOut(pid))).isFalse();

        assertThat(queueService.enter(USER_BASE + 1, pid).status()).isEqualTo(QueueTokenStatus.PROCESSING);
    }

    @Test
    @DisplayName("매진이면 대기자의 순서 조회는 SOLD_OUT이고 입장한 사람은 PROCESSING이며, 줄은 그대로라 매진이 풀리면 이어서 기다린다")
    void statusIsSoldOutForWaitersOnly() {
        long pid = openPerformance();
        QueueService service = serviceWithCapacity(1);
        String admitted = service.enter(USER_BASE, pid).queueToken();
        String waiting = service.enter(USER_BASE + 1, pid).queueToken();
        markSoldOut(pid, true);

        assertThat(service.status(USER_BASE + 1, pid, waiting)).isEqualTo(QueueStatusResult.soldOut());
        assertThat(service.status(USER_BASE, pid, admitted)).isEqualTo(QueueStatusResult.processing());
        assertThat(service.status(USER_BASE + 1, pid, USER_BASE + 1 + ".unknown"))
                .isEqualTo(QueueStatusResult.soldOut());
        assertThat(redis.opsForZSet().zCard(RedisKeys.queueWaiting(pid))).isEqualTo(1);
        assertThat(service.isAdmitted(USER_BASE, pid, admitted)).isTrue();

        markSoldOut(pid, false);
        assertThat(service.status(USER_BASE + 1, pid, waiting).status()).isEqualTo(QueueTokenStatus.WAITING);
        assertThat(service.status(USER_BASE + 1, pid, waiting).position()).isEqualTo(1);
    }

    private void markSoldOut(long pid, boolean soldOut) {
        seatMapRedisStore.putSoldOut(pid, soldOut, properties.seatmap().snapshotTtl());
    }

    private Double leaveDeadline(long pid, String token) {
        return redis.opsForZSet().score(RedisKeys.queueLeaving(pid), token);
    }

    private Double admittedAt(long pid, String token) {
        return redis.opsForZSet().score(RedisKeys.queueAdmitted(pid), token);
    }

    private QueueService serviceWithCapacity(int capacity) {
        return services.queueService()
                .properties(TestProperties.from(properties).queueCapacity(capacity).build())
                .build();
    }

    private long openPerformance() {
        return fixtures.newPerformance(1, 1, 1);
    }
}
