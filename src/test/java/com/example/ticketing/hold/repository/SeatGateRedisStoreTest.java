package com.example.ticketing.hold.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.GateResult;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestRedis;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

@IntegrationTest
class SeatGateRedisStoreTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final long TOLERANCE_MS = 5_000;

    @Autowired
    private SeatGateRedisStore seatGateRedisStore;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestRedis testRedis;

    private StringRedisTemplate redis;
    private long pid;
    private long userA;
    private long userB;
    private long seat1;
    private long seat2;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        redis = testRedis.template();
        pid = fixtures.redisOnlyPerformanceId();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        userA = random.nextLong(1, 1_000_000_000L);
        userB = userA + 1;
        seat1 = random.nextLong(1, 1_000_000_000L);
        seat2 = seat1 + 1;
        seats = List.of(seat1, seat2);
    }

    @Test
    @DisplayName("선점에 성공하면 사용자·좌석 키에 토큰을 쓰고 수명을 건다")
    void holdWritesKeys() {
        GateResult result = seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);

        assertThat(result).isEqualTo(GateResult.ok());
        for (String key : List.of(userHoldKey(userA), seatKey(seat1), seatKey(seat2))) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("tA");
            assertThat(testRedis.pttl(key)).isBetween(TTL.toMillis() - TOLERANCE_MS, TTL.toMillis());
        }
        assertThat(testRedis.keys(pid)).hasSize(3);
    }

    @Test
    @DisplayName("이미 선점한 사용자가 또 잡으면 USER_HAS_HOLD이고 키를 쓰지 않는다")
    void userHasHoldWritesNothing() {
        seatGateRedisStore.tryHold(new GateHold(userA, pid, "tFirst", List.of(seat1)), TTL);
        Map<String, String> before = snapshot();

        GateResult result = seatGateRedisStore.tryHold(new GateHold(userA, pid, "tSecond", List.of(seat2)), TTL);

        assertThat(result.type()).isEqualTo(GateResult.Type.USER_HAS_HOLD);
        assertThat(result.seatId()).isNull();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(redis.hasKey(seatKey(seat2))).isFalse();
    }

    @Test
    @DisplayName("잡힌 좌석이 섞이면 SEAT_TAKEN이고 키를 쓰지 않는다")
    void seatTakenWritesNothing() {
        seatGateRedisStore.tryHold(new GateHold(userB, pid, "tB", List.of(seat2)), TTL);
        Map<String, String> before = snapshot();

        GateResult result = seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);

        assertThat(result).isEqualTo(GateResult.seatTaken(seat2));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(redis.hasKey(userHoldKey(userA))).isFalse();
        assertThat(redis.hasKey(seatKey(seat1))).isFalse();
    }

    @Test
    @DisplayName("쿨다운 좌석이 섞이면 COOLDOWN이고 다른 사용자는 잡을 수 있다")
    void cooldownWritesNothing() {
        seatGateRedisStore.setReholdCooldown(new GateHold(userA, pid, "cooldown", List.of(seat2)), Duration.ofMinutes(10));
        Map<String, String> before = snapshot();

        GateResult result = seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);

        assertThat(result).isEqualTo(GateResult.cooldown(seat2));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(seatGateRedisStore.tryHold(new GateHold(userB, pid, "tB", seats), TTL)).isEqualTo(GateResult.ok());
    }

    @Test
    @DisplayName("해제(release)는 토큰이 같은 키만 지우고 남의 키는 남긴다")
    void releaseOnlyOwnKeys() {
        seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);
        redis.opsForValue().set(seatKey(seat2), "tOther", TTL);

        long deleted = seatGateRedisStore.release(new GateHold(userA, pid, "tA", seats));

        assertThat(deleted).isEqualTo(2);
        assertThat(redis.hasKey(userHoldKey(userA))).isFalse();
        assertThat(redis.hasKey(seatKey(seat1))).isFalse();
        assertThat(redis.opsForValue().get(seatKey(seat2))).isEqualTo("tOther");
    }

    @Test
    @DisplayName("좌석만 해제(releaseSeatsOnly)하면 사용자 키는 남는다")
    void releaseSeatsOnlyKeepsUserHold() {
        seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);

        long deleted = seatGateRedisStore.releaseSeatsOnly(new GateHold(userA, pid, "tA", seats));

        assertThat(deleted).isEqualTo(2);
        assertThat(redis.hasKey(seatKey(seat1))).isFalse();
        assertThat(redis.hasKey(seatKey(seat2))).isFalse();
        assertThat(redis.opsForValue().get(userHoldKey(userA))).isEqualTo("tA");
    }

    @Test
    @DisplayName("연장(extend)은 토큰이 같은 키만 늘리고 남의 키 수명은 그대로 둔다")
    void extendOnlyOwnKeys() {
        seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);
        redis.opsForValue().set(seatKey(seat2), "tOther", Duration.ofSeconds(60));
        Duration extended = Duration.ofMinutes(11);

        long count = seatGateRedisStore.extend(new GateHold(userA, pid, "tA", seats), extended);

        assertThat(count).isEqualTo(2);
        assertThat(testRedis.pttl(userHoldKey(userA))).isBetween(extended.toMillis() - TOLERANCE_MS, extended.toMillis());
        assertThat(testRedis.pttl(seatKey(seat1))).isBetween(extended.toMillis() - TOLERANCE_MS, extended.toMillis());
        assertThat(redis.opsForValue().get(seatKey(seat2))).isEqualTo("tOther");
        assertThat(testRedis.pttl(seatKey(seat2))).isBetween(1L, 60_000L);
    }

    @Test
    @DisplayName("확정(confirm)하면 좌석 키는 게이트 마감 때 만료되고 사용자 키는 지워진다")
    void confirmMovesExpiryToGateClose() {
        seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);
        redis.opsForValue().set(seatKey(seat2), "tOther", Duration.ofSeconds(60));
        Instant gateCloseAt = Instant.now().plus(Duration.ofHours(1));

        long count = seatGateRedisStore.confirm(new GateHold(userA, pid, "tA", seats), gateCloseAt);

        long expectedMs = Duration.between(Instant.now(), gateCloseAt).toMillis();
        assertThat(count).isEqualTo(1);
        assertThat(redis.hasKey(userHoldKey(userA))).isFalse();
        assertThat(redis.opsForValue().get(seatKey(seat1))).isEqualTo("tA");
        assertThat(testRedis.pttl(seatKey(seat1))).isBetween(expectedMs - TOLERANCE_MS, expectedMs + TOLERANCE_MS);
        assertThat(redis.opsForValue().get(seatKey(seat2))).isEqualTo("tOther");
        assertThat(testRedis.pttl(seatKey(seat2))).isBetween(1L, 60_000L);
    }

    @Test
    @DisplayName("확정 시각이 이미 지났으면 좌석 키가 바로 사라진다")
    void confirmPastRemovesSeatKeys() {
        seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);

        long count = seatGateRedisStore.confirm(new GateHold(userA, pid, "tA", seats), Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(count).isEqualTo(2);
        assertThat(testRedis.keys(pid)).isEmpty();
    }

    @Test
    @DisplayName("확정(confirm)은 토큰이 다른 사용자 키를 지우지 않는다")
    void confirmKeepsForeignUserHold() {
        seatGateRedisStore.tryHold(new GateHold(userA, pid, "tA", seats), TTL);
        redis.opsForValue().set(userHoldKey(userA), "tOther", TTL);

        seatGateRedisStore.confirm(new GateHold(userA, pid, "tA", seats), Instant.now().plus(Duration.ofHours(1)));

        assertThat(redis.opsForValue().get(userHoldKey(userA))).isEqualTo("tOther");
    }

    @Test
    @DisplayName("재선점 쿨다운을 걸면 좌석마다 쿨다운 키가 생긴다")
    void cooldownKeys() {
        Duration cooldown = Duration.ofMinutes(10);

        seatGateRedisStore.setReholdCooldown(new GateHold(userA, pid, "cooldown", seats), cooldown);

        for (long seatId : seats) {
            String key = RedisKeys.rehold(userA, pid, seatId);
            assertThat(redis.opsForValue().get(key)).isEqualTo("1");
            assertThat(testRedis.pttl(key)).isBetween(cooldown.toMillis() - TOLERANCE_MS, cooldown.toMillis());
        }
        assertThat(testRedis.keys(pid)).hasSize(2);
    }

    @Test
    @DisplayName("키에는 회차 해시 태그가 들어가고 좌석 ID를 키에서 되읽는다")
    void keysCarryHashTag() {
        assertThat(RedisKeys.seat(7, 42)).isEqualTo("seat:{p:7}:42");
        assertThat(RedisKeys.userHold(3, 7)).isEqualTo("userhold:{p:7}:3");
        assertThat(RedisKeys.rehold(3, 7, 42)).isEqualTo("rehold:{p:7}:3:42");
        assertThat(RedisKeys.seatIdOf("seat:{p:7}:42")).isEqualTo(42);
        assertThat(RedisKeys.seatIdOf("rehold:{p:7}:3:42")).isEqualTo(42);
        assertThat(RedisKeys.seatPattern(7)).isEqualTo("seat:{p:7}:*");
        assertThat(RedisKeys.userHoldPattern(7)).isEqualTo("userhold:{p:7}:*");
        assertThat(RedisKeys.seatMap(7)).isEqualTo("seatmap:{p:7}");
        assertThat(RedisKeys.seatMapLease(7)).isEqualTo("seatmap:lease:{p:7}");
        assertThat(RedisKeys.schedulerLease("orphan")).isEqualTo("sched:lease:orphan");
    }

    private Map<String, String> snapshot() {
        Map<String, String> values = new HashMap<>();
        for (String key : testRedis.keys(pid)) {
            values.put(key, redis.opsForValue().get(key));
        }
        return values;
    }

    private String seatKey(long seatId) {
        return RedisKeys.seat(pid, seatId);
    }

    private String userHoldKey(long userId) {
        return RedisKeys.userHold(userId, pid);
    }
}
