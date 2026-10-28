package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.hold.dto.HoldCreated;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SeatHoldGateTest {

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private HoldExpiryTransaction expiryTransaction;
    @Autowired
    private SeatGateRedisStore gate;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private TicketingRedis bookingRedis;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;

    private StringRedisTemplate redis;
    private long pid;
    private List<Long> ids;

    @BeforeEach
    void setUp() {
        redis = bookingRedis.template();
        pid = fixtures.newPerformance(1, 1, 4);
        ids = jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }

    @AfterEach
    void tearDown() {
        redisKeyCleaner.clean(pid);
    }

    @Test
    @DisplayName("게이트 키를 지우면 B는 게이트를 통과하지만 DB가 SEAT_STATE_CHANGED로 거절하고, B가 쓴 키는 지워진다")
    void deletedGateKeyFallsBackToDb() {
        List<Long> target = ids.subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        HoldCreated holdA = seatHoldService.hold(userA, pid, target, fixtures.admit(pid, userA));
        String tokenA = holdToken(holdA.holdId());
        assertThat(redis.opsForValue().get(RedisKeys.seat(pid, target.get(0)))).isEqualTo(tokenA);

        redis.delete(target.stream().map(seatId -> RedisKeys.seat(pid, seatId)).toList());
        double gateOkBefore = gateCounter("ok");
        double stateChangedBefore = dbCounter("state_changed");

        String queueToken = fixtures.admit(pid, userB);
        assertThatThrownBy(() -> seatHoldService.hold(userB, pid, target, queueToken))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.SEAT_STATE_CHANGED);

        assertThat(gateCounter("ok") - gateOkBefore).isEqualTo(1);
        assertThat(dbCounter("state_changed") - stateChangedBefore).isEqualTo(1);
        // B가 게이트에 쓴 userhold·좌석 키는 release로 지워졌고 A의 userhold는 남는다
        assertThat(redis.hasKey(RedisKeys.userHold(pid, userB))).isFalse();
        assertThat(target).allSatisfy(seatId -> assertThat(redis.hasKey(RedisKeys.seat(pid, seatId))).isFalse());
        assertThat(redis.opsForValue().get(RedisKeys.userHold(pid, userA))).isEqualTo(tokenA);

        assertThat(holdStatus(holdA.holdId())).isEqualTo("HELD");
        assertThat(heldSeats(holdA.holdId())).containsExactlyElementsOf(target);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?",
                Long.class, pid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'",
                Long.class, pid)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사용자의 두 번째 선점은 게이트가 USER_HAS_HOLD로 막고 DB에 닿지 않는다")
    void userHasHoldStopsAtGate() {
        long userId = fixtures.newUser();
        seatHoldService.hold(userId, pid, List.of(ids.get(0)), fixtures.admit(pid, userId));
        double dbBefore = dbTotal();
        double gateBefore = gateCounter("user_has_hold");

        String queueToken = fixtures.admit(pid, userId);
        assertThatThrownBy(() -> seatHoldService.hold(userId, pid, List.of(ids.get(1)), queueToken))
                .extracting("code").isEqualTo(ErrorCode.USER_HAS_HOLD);

        assertThat(dbTotal() - dbBefore).isZero();
        assertThat(gateCounter("user_has_hold") - gateBefore).isEqualTo(1);
        assertThat(redis.hasKey(RedisKeys.seat(pid, ids.get(1)))).isFalse();
        assertThat(seatStatus(ids.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("만료되면 게이트 키가 지워지고 쿨다운이 걸려, 같은 사용자는 409 COOLDOWN이고 다른 사용자는 잡는다")
    void reholdCooldownAfterExpiry() {
        long seat = ids.get(0);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        HoldCreated holdA = seatHoldService.hold(userA, pid, List.of(seat), fixtures.admit(pid, userA));
        jdbcTemplate.update("UPDATE seat_hold SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = ?", holdA.holdId());
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 MINUTE WHERE hold_id = ?", holdA.holdId());

        // 테스트 컨텍스트에서는 스케줄러 빈이 꺼져 있어 같은 빈들로 직접 만든다. 다른 테스트가 남긴 만료 hold가 앞에 있을 수 있어 치워질 때까지 돈다
        HoldExpiryScheduler scheduler = new HoldExpiryScheduler(expiryTransaction, gate, properties, meterRegistry);
        for (int i = 0; i < 20 && !"EXPIRED".equals(holdStatus(holdA.holdId())); i++) {
            scheduler.scan();
        }

        assertThat(holdStatus(holdA.holdId())).isEqualTo("EXPIRED");
        assertThat(redis.hasKey(RedisKeys.seat(pid, seat))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(pid, userA))).isFalse();
        String cooldownKey = RedisKeys.rehold(pid, userA, seat);
        assertThat(redis.opsForValue().get(cooldownKey)).isEqualTo("1");
        assertThat(redis.getExpire(cooldownKey, TimeUnit.SECONDS))
                .isGreaterThan(properties.hold().reholdCooldown().minusSeconds(10).toSeconds())
                .isLessThanOrEqualTo(properties.hold().reholdCooldown().toSeconds());
        double dbBefore = dbTotal();

        String queueToken = fixtures.admit(pid, userA);
        assertThatThrownBy(() -> seatHoldService.hold(userA, pid, List.of(seat), queueToken))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException be = (ApiException) e;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.COOLDOWN);
                    assertThat(be.getDetail()).containsEntry("seatId", seat);
                });
        assertThat(dbTotal() - dbBefore).isZero();

        HoldCreated holdB = seatHoldService.hold(userB, pid, List.of(seat), fixtures.admit(pid, userB));
        assertThat(heldSeats(holdB.holdId())).containsExactly(seat);
        // 쿨다운은 놓친 좌석에만 걸린다
        HoldCreated holdA2 = seatHoldService.hold(userA, pid, List.of(ids.get(1)), fixtures.admit(pid, userA));
        assertThat(heldSeats(holdA2.holdId())).containsExactly(ids.get(1));
    }

    @Test
    @DisplayName("선점을 취소하면 게이트 키가 지워지고 다른 사용자가 바로 잡는다")
    void releaseClearsGateKeys() {
        List<Long> target = ids.subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        HoldCreated holdA = seatHoldService.hold(userA, pid, target, fixtures.admit(pid, userA));
        assertThat(redisKeyCleaner.gateKeys(pid)).hasSize(3);

        seatHoldService.release(userA, holdA.holdId());

        assertThat(redisKeyCleaner.gateKeys(pid)).isEmpty();
        HoldCreated holdB = seatHoldService.hold(userB, pid, target, fixtures.admit(pid, userB));
        assertThat(heldSeats(holdB.holdId())).containsExactlyElementsOf(target);
        assertThat(redis.opsForValue().get(RedisKeys.seat(pid, target.get(0))))
                .isEqualTo(holdToken(holdB.holdId()));
    }

    private String holdToken(long holdId) {
        return jdbcTemplate.queryForObject("SELECT hold_token FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    private String holdStatus(long holdId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    private String seatStatus(long seatId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seatId);
    }

    private List<Long> heldSeats(long holdId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM seat WHERE performance_id = ? AND hold_id = ? AND status = 'HELD' ORDER BY id",
                Long.class, pid, holdId);
    }

    private double dbTotal() {
        return dbCounter("ok") + dbCounter("state_changed") + dbCounter("user_has_hold");
    }

    private double dbCounter(String result) {
        return meterRegistry.counter(Metrics.HOLD_DB, Tag.RESULT, result).count();
    }

    private double gateCounter(String result) {
        return meterRegistry.counter(Metrics.HOLD_GATE, Tag.RESULT, result).count();
    }
}
