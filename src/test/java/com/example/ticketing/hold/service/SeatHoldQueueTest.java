package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.queue.service.QueueService;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;

@IntegrationTest
class SeatHoldQueueTest {

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private TestServices services;
    @Autowired
    private Counters counters;

    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis = testRedis.template();
    }

    @Test
    @DisplayName("처리열 토큰이 없거나 맞지 않으면 403 NOT_ADMITTED이고 게이트·DB에 닿지 않는다")
    void notAdmittedStopsBeforeGateAndDb() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = db.seatIds(pid).subList(0, 1);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        String tokenB = fixtures.admit(pid, userB);
        String expiredA = fixtures.admit(pid, userA);
        redis.opsForZSet().add(RedisKeys.queueProcessing(pid), expiredA, System.currentTimeMillis() - 1_000);
        double gateBefore = counters.sum(Metrics.HOLD_GATE);
        double dbBefore = counters.sum(Metrics.HOLD_DB);

        for (String token : new String[]{null, "", "  ", tokenB, userA + "." + UUID.randomUUID(), expiredA}) {
            assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userA, pid, target, token)))
                    .as("token=%s", token)
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo(ErrorCode.NOT_ADMITTED);
        }

        assertThat(counters.sum(Metrics.HOLD_GATE)).isEqualTo(gateBefore);
        assertThat(counters.sum(Metrics.HOLD_DB)).isEqualTo(dbBefore);
        assertThat(db.holdCount(pid)).isZero();
        assertThat(testRedis.gateKeys(pid)).isEmpty();
        // 남의 토큰을 써 봐도 그 사람의 자리는 그대로다
        assertThat(processingScore(pid, tokenB)).isNotNull();
    }

    @Test
    @DisplayName("게이트가 SEAT_TAKEN으로 거절해도 토큰이 남아 다른 좌석을 잡을 때 소모된다")
    void gateRejectionKeepsToken() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = db.seatIds(pid);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        seatHoldService.hold(new CreateHoldCommand(userA, pid, List.of(ids.get(0)), fixtures.admit(pid, userA)));
        String tokenB = fixtures.admit(pid, userB);

        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userB, pid, List.of(ids.get(0)), tokenB)))
                .extracting("code").isEqualTo(ErrorCode.SEAT_TAKEN);
        assertThat(processingScore(pid, tokenB)).isNotNull();
        assertThat(redis.opsForValue().get(RedisKeys.queueUser(userB, pid))).isEqualTo(tokenB);

        CreateHoldResult holdB = seatHoldService.hold(new CreateHoldCommand(userB, pid, List.of(ids.get(1)), tokenB));

        assertThat(holdB.seats()).extracting(s -> s.id()).containsExactly(ids.get(1));
        assertThat(processingScore(pid, tokenB)).isNull();
        assertThat(redis.hasKey(RedisKeys.queueUser(userB, pid))).isFalse();
    }

    @Test
    @DisplayName("DB 판정이 SEAT_STATE_CHANGED로 거절해도 토큰을 소모하지 않는다")
    void dbRejectionKeepsToken() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = db.seatIds(pid).subList(0, 1);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        seatHoldService.hold(new CreateHoldCommand(userA, pid, target, fixtures.admit(pid, userA)));
        redis.delete(RedisKeys.seat(pid, target.get(0)));
        String tokenB = fixtures.admit(pid, userB);

        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userB, pid, target, tokenB)))
                .extracting("code").isEqualTo(ErrorCode.SEAT_STATE_CHANGED);

        assertThat(processingScore(pid, tokenB)).isNotNull();
        assertThat(redis.opsForValue().get(RedisKeys.queueUser(userB, pid))).isEqualTo(tokenB);
    }

    @Test
    @DisplayName("선점에 성공하면 토큰이 소모돼 취소 뒤 같은 토큰으로 다시 잡으면 403이다")
    void successConsumesToken() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = db.seatIds(pid).subList(0, 2);
        long userId = fixtures.newUser();
        String token = fixtures.admit(pid, userId);

        CreateHoldResult hold = seatHoldService.hold(new CreateHoldCommand(userId, pid, target, token));

        assertThat(processingScore(pid, token)).isNull();
        assertThat(redis.hasKey(RedisKeys.queueUser(userId, pid))).isFalse();

        seatHoldService.release(userId, hold.holdId());
        double gateBefore = counters.sum(Metrics.HOLD_GATE);
        double dbBefore = counters.sum(Metrics.HOLD_DB);

        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userId, pid, target, token)))
                .extracting("code").isEqualTo(ErrorCode.NOT_ADMITTED);
        assertThat(counters.sum(Metrics.HOLD_GATE)).isEqualTo(gateBefore);
        assertThat(counters.sum(Metrics.HOLD_DB)).isEqualTo(dbBefore);
        assertThat(db.holdCount(pid)).isEqualTo(1);
    }

    @Test
    @DisplayName("토큰 소모가 Redis 예외로 실패해도 선점은 성공하고 반영 실패만 센다")
    void consumeFailureIsSwallowed() {
        long pid = fixtures.newPerformance(1, 1, 4);
        long userId = fixtures.newUser();
        String token = userId + "." + UUID.randomUUID();
        QueueRedisStore store = mock(QueueRedisStore.class);
        when(store.processingScore(pid, token)).thenReturn((double) System.currentTimeMillis() + 60_000);
        when(store.consume(userId, pid, token))
                .thenThrow(new RedisUnavailableException(new QueryTimeoutException("consume timeout")));
        QueueService queue = services.queueService().store(store).build();
        double failureBefore = counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "queue_consume");

        CreateHoldResult hold = services.seatHoldService().queueService(queue).build()
                .hold(new CreateHoldCommand(userId, pid, db.seatIds(pid).subList(0, 1), token));

        verify(store).consume(userId, pid, token);
        assertThat(counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "queue_consume") - failureBefore)
                .isEqualTo(1);
        assertThat(db.holdStatus(hold.holdId())).isEqualTo("HELD");
    }

    @Test
    @DisplayName("처리열 확인이 Redis 예외로 실패하면 게이트를 건너뛰고 DB 판정으로 잡는다")
    void queueCheckFailureFailsOpen() {
        long pid = fixtures.newPerformance(1, 1, 4);
        long userId = fixtures.newUser();
        QueueService deadQueue = services.queueService().store(TestServices.queueStore(testRedis.dead())).build();
        SeatGateRedisStore spyGate = mock(SeatGateRedisStore.class);
        SeatHoldService service = services.seatHoldService().gateStore(spyGate).queueService(deadQueue).build();

        // 토큰 주인 확인은 Redis 없이 하므로 장애 중에도 남의 토큰은 403이다
        assertThatThrownBy(() -> service.hold(new CreateHoldCommand(userId, pid, db.seatIds(pid).subList(0, 1),
                (userId + 1) + "." + UUID.randomUUID())))
                .extracting("code").isEqualTo(ErrorCode.NOT_ADMITTED);

        double gateErrorBefore = counters.count(Metrics.HOLD_GATE, Tag.RESULT, "error");
        double dbOkBefore = counters.count(Metrics.HOLD_DB, Tag.RESULT, "ok");
        double acquiredBefore = counters.count(Metrics.HOLD_FALLBACK, Tag.RESULT, "acquired");
        double consumeFailureBefore = counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "queue_consume");

        CreateHoldResult hold = service.hold(
                new CreateHoldCommand(userId, pid, db.seatIds(pid).subList(0, 1), userId + "." + UUID.randomUUID()));

        assertThat(hold.holdId()).isPositive();
        verifyNoInteractions(spyGate);
        assertThat(counters.count(Metrics.HOLD_GATE, Tag.RESULT, "error") - gateErrorBefore).isEqualTo(1);
        assertThat(counters.count(Metrics.HOLD_DB, Tag.RESULT, "ok") - dbOkBefore).isEqualTo(1);
        assertThat(counters.count(Metrics.HOLD_FALLBACK, Tag.RESULT, "acquired") - acquiredBefore).isEqualTo(1);
        assertThat(counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "queue_consume") - consumeFailureBefore)
                .isEqualTo(1);
        assertThat(db.holdCount(pid)).isEqualTo(1);
    }

    @Test
    @DisplayName("처리열이 필수가 아니면 토큰 없이 잡고 처리열을 건드리지 않는다")
    void queueNotRequired() {
        long pid = fixtures.newPerformance(1, 1, 4);
        long userId = fixtures.newUser();
        QueueService queue = mock(QueueService.class);
        TicketingProperties noQueue = TestProperties.from(properties).queueRequired(false).build();

        CreateHoldResult hold = services.seatHoldService().queueService(queue).properties(noQueue).build()
                .hold(new CreateHoldCommand(userId, pid, db.seatIds(pid).subList(0, 1), null));

        assertThat(hold.holdId()).isPositive();
        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("게이트가 꺼져 있어도 처리열 확인과 소모는 한다")
    void gateOffStillChecksQueue() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = db.seatIds(pid).subList(0, 1);
        long userId = fixtures.newUser();
        SeatGateRedisStore offGate = mock(SeatGateRedisStore.class);
        TicketingProperties off = TestProperties.from(properties).gateEnabled(false).queueRequired(true).build();
        SeatHoldService gateOff = services.seatHoldService().gateStore(offGate).properties(off).build();

        assertThatThrownBy(() -> gateOff.hold(new CreateHoldCommand(userId, pid, target, null)))
                .extracting("code").isEqualTo(ErrorCode.NOT_ADMITTED);

        String token = fixtures.admit(pid, userId);
        gateOff.hold(new CreateHoldCommand(userId, pid, target, token));

        assertThat(processingScore(pid, token)).isNull();
        verifyNoInteractions(offGate);
    }

    private Double processingScore(long pid, String token) {
        return redis.opsForZSet().score(RedisKeys.queueProcessing(pid), token);
    }
}
