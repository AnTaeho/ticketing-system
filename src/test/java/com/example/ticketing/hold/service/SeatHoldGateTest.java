package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.util.List;
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
                new CreateHoldCommand(userA, pid, target));
        String tokenA = db.holdToken(holdA.holdId());
        assertThat(redis.opsForValue().get(RedisKeys.seat(pid, target.get(0)))).isEqualTo(tokenA);

        redis.delete(target.stream().map(seatId -> RedisKeys.seat(pid, seatId)).toList());

        assertThatThrownBy(() -> seatHoldService.hold(new CreateHoldCommand(userB, pid, target)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.SEAT_STATE_CHANGED);

        // B가 게이트에 쓴 좌석 키는 release로 지워졌다
        assertThat(target).allSatisfy(seatId -> assertThat(redis.hasKey(RedisKeys.seat(pid, seatId))).isFalse());

        assertThat(db.holdStatus(holdA.holdId())).isEqualTo("HELD");
        assertThat(db.heldSeats(pid, holdA.holdId())).containsExactlyElementsOf(target);
        assertThat(db.holdCount(pid)).isEqualTo(1);
        assertThat(db.heldSeatCount(pid)).isEqualTo(2);
    }

    @Test
    @DisplayName("선점을 취소하면 게이트 키가 지워지고 다른 사용자가 바로 잡는다")
    void releaseClearsGateKeys() {
        List<Long> target = ids.subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        CreateHoldResult holdA = seatHoldService.hold(
                new CreateHoldCommand(userA, pid, target));
        assertThat(testRedis.keys(pid)).hasSize(2);

        seatHoldService.release(userA, holdA.holdId());

        assertThat(testRedis.keys(pid)).isEmpty();
        CreateHoldResult holdB = seatHoldService.hold(
                new CreateHoldCommand(userB, pid, target));
        assertThat(db.heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
        assertThat(redis.opsForValue().get(RedisKeys.seat(pid, target.get(0))))
                .isEqualTo(db.holdToken(holdB.holdId()));
    }
}
