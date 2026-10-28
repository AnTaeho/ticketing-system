package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

@IntegrationTest
class SeatHoldGateTest {

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
    private long pid;
    private List<Long> ids;

    @BeforeEach
    void setUp() {
        redis = testRedis.template();
        pid = fixtures.newPerformance(1, 1, 4);
        ids = db.seatIds(pid);
    }

    @Test
    @DisplayName("게이트 키가 지워져도 DB가 SEAT_STATE_CHANGED로 막고 B가 쓴 키는 지운다")
    void deletedGateKeyFallsBackToDb() {
        List<Long> target = ids.subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        CreateHoldResult holdA = seatHoldService.hold(
                new CreateHoldCommand(userA, pid, target, fixtures.admit(pid, userA)));
        String tokenA = db.holdToken(holdA.holdId());
        assertThat(redis.opsForValue().get(RedisKeys.seat(pid, target.get(0)))).isEqualTo(tokenA);

        redis.delete(target.stream().map(seatId -> RedisKeys.seat(pid, seatId)).toList());
        double gateOkBefore = counters.count(Metrics.HOLD_GATE, Tag.RESULT, "ok");
        double stateChangedBefore = counters.count(Metrics.HOLD_DB, Tag.RESULT, "state_changed");

        String queueToken = fixtures.admit(pid, userB);
        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userB, pid, target, queueToken)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.SEAT_STATE_CHANGED);

        assertThat(counters.count(Metrics.HOLD_GATE, Tag.RESULT, "ok") - gateOkBefore).isEqualTo(1);
        assertThat(counters.count(Metrics.HOLD_DB, Tag.RESULT, "state_changed") - stateChangedBefore).isEqualTo(1);
        // B가 게이트에 쓴 userhold·좌석 키는 release로 지워졌고 A의 userhold는 남는다
        assertThat(redis.hasKey(RedisKeys.userHold(userB, pid))).isFalse();
        assertThat(target).allSatisfy(seatId -> assertThat(redis.hasKey(RedisKeys.seat(pid, seatId))).isFalse());
        assertThat(redis.opsForValue().get(RedisKeys.userHold(userA, pid))).isEqualTo(tokenA);

        assertThat(db.holdStatus(holdA.holdId())).isEqualTo("HELD");
        assertThat(db.heldSeats(pid, holdA.holdId())).containsExactlyElementsOf(target);
        assertThat(db.holdCount(pid)).isEqualTo(1);
        assertThat(db.heldSeatCount(pid)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사용자의 두 번째 선점은 게이트가 USER_HAS_HOLD로 막고 DB에 닿지 않는다")
    void userHasHoldStopsAtGate() {
        long userId = fixtures.newUser();
        seatHoldService.hold(new CreateHoldCommand(userId, pid, List.of(ids.get(0)), fixtures.admit(pid, userId)));
        double dbBefore = counters.sum(Metrics.HOLD_DB);
        double gateBefore = counters.count(Metrics.HOLD_GATE, Tag.RESULT, "user_has_hold");

        String queueToken = fixtures.admit(pid, userId);
        assertThatThrownBy(() -> seatHoldService.hold(
                new CreateHoldCommand(userId, pid, List.of(ids.get(1)), queueToken)))
                .extracting("code").isEqualTo(ErrorCode.USER_HAS_HOLD);

        assertThat(counters.sum(Metrics.HOLD_DB) - dbBefore).isZero();
        assertThat(counters.count(Metrics.HOLD_GATE, Tag.RESULT, "user_has_hold") - gateBefore).isEqualTo(1);
        assertThat(redis.hasKey(RedisKeys.seat(pid, ids.get(1)))).isFalse();
        assertThat(db.seatStatus(ids.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("선점이 만료되면 같은 사용자만 그 좌석을 COOLDOWN으로 다시 못 잡는다")
    void reholdCooldownAfterExpiry() {
        long seat = ids.get(0);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        CreateHoldResult holdA = seatHoldService.hold(
                new CreateHoldCommand(userA, pid, List.of(seat), fixtures.admit(pid, userA)));
        db.expireHoldAndSeats(holdA.holdId());

        // 스케줄러 빈은 테스트에서 꺼져 있다. 남이 남긴 만료 선점이 앞에 있을 수 있어 치워질 때까지 돈다
        HoldExpiryScheduler scheduler = services.holdExpiryScheduler();
        for (int i = 0; i < 20 && !"EXPIRED".equals(db.holdStatus(holdA.holdId())); i++) {
            scheduler.run();
        }

        assertThat(db.holdStatus(holdA.holdId())).isEqualTo("EXPIRED");
        assertThat(redis.hasKey(RedisKeys.seat(pid, seat))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(userA, pid))).isFalse();
        String cooldownKey = RedisKeys.rehold(userA, pid, seat);
        assertThat(redis.opsForValue().get(cooldownKey)).isEqualTo("1");
        assertThat(redis.getExpire(cooldownKey, TimeUnit.SECONDS))
                .isGreaterThan(properties.hold().reholdCooldown().minusSeconds(10).toSeconds())
                .isLessThanOrEqualTo(properties.hold().reholdCooldown().toSeconds());
        double dbBefore = counters.sum(Metrics.HOLD_DB);

        String queueToken = fixtures.admit(pid, userA);
        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userA, pid, List.of(seat), queueToken)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException be = (ApiException) e;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.COOLDOWN);
                    assertThat(be.getDetail()).containsEntry("seatId", seat);
                });
        assertThat(counters.sum(Metrics.HOLD_DB) - dbBefore).isZero();

        CreateHoldResult holdB = seatHoldService.hold(
                new CreateHoldCommand(userB, pid, List.of(seat), fixtures.admit(pid, userB)));
        assertThat(db.heldSeats(pid, holdB.holdId())).containsExactly(seat);
        // 쿨다운은 놓친 좌석에만 걸린다
        CreateHoldResult holdA2 = seatHoldService.hold(
                new CreateHoldCommand(userA, pid, List.of(ids.get(1)), fixtures.admit(pid, userA)));
        assertThat(db.heldSeats(pid, holdA2.holdId())).containsExactly(ids.get(1));
    }

    @Test
    @DisplayName("선점을 취소하면 게이트 키가 지워지고 다른 사용자가 바로 잡는다")
    void releaseClearsGateKeys() {
        List<Long> target = ids.subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        CreateHoldResult holdA = seatHoldService.hold(
                new CreateHoldCommand(userA, pid, target, fixtures.admit(pid, userA)));
        assertThat(testRedis.gateKeys(pid)).hasSize(3);

        seatHoldService.release(userA, holdA.holdId());

        assertThat(testRedis.gateKeys(pid)).isEmpty();
        CreateHoldResult holdB = seatHoldService.hold(
                new CreateHoldCommand(userB, pid, target, fixtures.admit(pid, userB)));
        assertThat(db.heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
        assertThat(redis.opsForValue().get(RedisKeys.seat(pid, target.get(0))))
                .isEqualTo(db.holdToken(holdB.holdId()));
    }
}
