package com.example.ticketing.hold.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.hold.domain.GateResult;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
    private SeatGateRedisStore gate;
    @Autowired
    private TicketingRedis bookingRedis;
    @Autowired
    private RedisKeyCleaner cleaner;

    private StringRedisTemplate redis;
    private long pid;
    private long userA;
    private long userB;
    private long seat1;
    private long seat2;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        redis = bookingRedis.template();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        pid = random.nextLong(1_000_000_000_000L, 9_000_000_000_000L);
        userA = random.nextLong(1, 1_000_000_000L);
        userB = userA + 1;
        seat1 = random.nextLong(1, 1_000_000_000L);
        seat2 = seat1 + 1;
        seats = List.of(seat1, seat2);
    }

    @AfterEach
    void tearDown() {
        cleaner.clean(pid);
    }

    @Test
    @DisplayName("hold.lua 성공: userhold와 좌석 키가 토큰 값으로 쓰이고 TTL이 ttl 근처다")
    void holdWritesKeys() {
        GateResult result = gate.tryHold(pid, userA, seats, "tA", TTL);

        assertThat(result).isEqualTo(GateResult.ok());
        for (String key : List.of(userHoldKey(userA), seatKey(seat1), seatKey(seat2))) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("tA");
            assertThat(pttl(key)).isBetween(TTL.toMillis() - TOLERANCE_MS, TTL.toMillis());
        }
        assertThat(cleaner.keys(pid)).hasSize(3);
    }

    @Test
    @DisplayName("이미 선점이 있는 사용자는 USER_HAS_HOLD이고 아무 키도 새로 쓰지 않는다")
    void userHasHoldWritesNothing() {
        gate.tryHold(pid, userA, List.of(seat1), "tFirst", TTL);
        Map<String, String> before = snapshot();

        GateResult result = gate.tryHold(pid, userA, List.of(seat2), "tSecond", TTL);

        assertThat(result.type()).isEqualTo(GateResult.Type.USER_HAS_HOLD);
        assertThat(result.seatId()).isNull();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(redis.hasKey(seatKey(seat2))).isFalse();
    }

    @Test
    @DisplayName("잡힌 좌석이 섞이면 SEAT_TAKEN(그 좌석)이고 아무 키도 새로 쓰지 않는다")
    void seatTakenWritesNothing() {
        gate.tryHold(pid, userB, List.of(seat2), "tB", TTL);
        Map<String, String> before = snapshot();

        GateResult result = gate.tryHold(pid, userA, seats, "tA", TTL);

        assertThat(result).isEqualTo(GateResult.seatTaken(seat2));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(redis.hasKey(userHoldKey(userA))).isFalse();
        assertThat(redis.hasKey(seatKey(seat1))).isFalse();
    }

    @Test
    @DisplayName("쿨다운 좌석이 섞이면 COOLDOWN(그 좌석)이고 아무 키도 새로 쓰지 않는다. 다른 사용자는 잡을 수 있다")
    void cooldownWritesNothing() {
        gate.setReholdCooldown(pid, userA, List.of(seat2), Duration.ofMinutes(10));
        Map<String, String> before = snapshot();

        GateResult result = gate.tryHold(pid, userA, seats, "tA", TTL);

        assertThat(result).isEqualTo(GateResult.cooldown(seat2));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(gate.tryHold(pid, userB, seats, "tB", TTL)).isEqualTo(GateResult.ok());
    }

    @Test
    @DisplayName("release는 토큰이 같은 키만 지우고 다른 토큰의 키는 남긴다")
    void releaseOnlyOwnKeys() {
        gate.tryHold(pid, userA, seats, "tA", TTL);
        redis.opsForValue().set(seatKey(seat2), "tOther", TTL);

        long deleted = gate.release(pid, userA, seats, "tA");

        assertThat(deleted).isEqualTo(2);
        assertThat(redis.hasKey(userHoldKey(userA))).isFalse();
        assertThat(redis.hasKey(seatKey(seat1))).isFalse();
        assertThat(redis.opsForValue().get(seatKey(seat2))).isEqualTo("tOther");
    }

    @Test
    @DisplayName("releaseSeatsOnly는 좌석 키만 지우고 userhold는 남긴다")
    void releaseSeatsOnlyKeepsUserHold() {
        gate.tryHold(pid, userA, seats, "tA", TTL);

        long deleted = gate.releaseSeatsOnly(pid, seats, "tA");

        assertThat(deleted).isEqualTo(2);
        assertThat(redis.hasKey(seatKey(seat1))).isFalse();
        assertThat(redis.hasKey(seatKey(seat2))).isFalse();
        assertThat(redis.opsForValue().get(userHoldKey(userA))).isEqualTo("tA");
    }

    @Test
    @DisplayName("extend는 토큰이 같은 키만 연장하고 다른 토큰 키의 TTL은 그대로다")
    void extendOnlyOwnKeys() {
        gate.tryHold(pid, userA, seats, "tA", TTL);
        redis.opsForValue().set(seatKey(seat2), "tOther", Duration.ofSeconds(60));
        Duration extended = Duration.ofMinutes(11);

        long count = gate.extend(pid, userA, seats, "tA", extended);

        assertThat(count).isEqualTo(2);
        assertThat(pttl(userHoldKey(userA))).isBetween(extended.toMillis() - TOLERANCE_MS, extended.toMillis());
        assertThat(pttl(seatKey(seat1))).isBetween(extended.toMillis() - TOLERANCE_MS, extended.toMillis());
        assertThat(redis.opsForValue().get(seatKey(seat2))).isEqualTo("tOther");
        assertThat(pttl(seatKey(seat2))).isBetween(1L, 60_000L);
    }

    @Test
    @DisplayName("confirm은 좌석 키를 gateCloseAt에 만료되게 하고 userhold를 지운다. 다른 토큰 키는 그대로다")
    void confirmMovesExpiryToGateClose() {
        gate.tryHold(pid, userA, seats, "tA", TTL);
        redis.opsForValue().set(seatKey(seat2), "tOther", Duration.ofSeconds(60));
        Instant gateCloseAt = Instant.now().plus(Duration.ofHours(1));

        long count = gate.confirm(pid, userA, seats, "tA", gateCloseAt);

        long expectedMs = Duration.between(Instant.now(), gateCloseAt).toMillis();
        assertThat(count).isEqualTo(1);
        assertThat(redis.hasKey(userHoldKey(userA))).isFalse();
        assertThat(redis.opsForValue().get(seatKey(seat1))).isEqualTo("tA");
        assertThat(pttl(seatKey(seat1))).isBetween(expectedMs - TOLERANCE_MS, expectedMs + TOLERANCE_MS);
        assertThat(redis.opsForValue().get(seatKey(seat2))).isEqualTo("tOther");
        assertThat(pttl(seatKey(seat2))).isBetween(1L, 60_000L);
    }

    @Test
    @DisplayName("confirm 시각이 과거면 좌석 키가 바로 사라진다")
    void confirmPastRemovesSeatKeys() {
        gate.tryHold(pid, userA, seats, "tA", TTL);

        long count = gate.confirm(pid, userA, seats, "tA", Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(count).isEqualTo(2);
        assertThat(cleaner.keys(pid)).isEmpty();
    }

    @Test
    @DisplayName("confirm은 토큰이 다른 userhold를 지우지 않는다")
    void confirmKeepsForeignUserHold() {
        gate.tryHold(pid, userA, seats, "tA", TTL);
        redis.opsForValue().set(userHoldKey(userA), "tOther", TTL);

        gate.confirm(pid, userA, seats, "tA", Instant.now().plus(Duration.ofHours(1)));

        assertThat(redis.opsForValue().get(userHoldKey(userA))).isEqualTo("tOther");
    }

    @Test
    @DisplayName("재선점 쿨다운 키가 좌석마다 생기고 TTL이 cooldown 근처다")
    void cooldownKeys() {
        Duration cooldown = Duration.ofMinutes(10);

        gate.setReholdCooldown(pid, userA, seats, cooldown);

        for (long seatId : seats) {
            String key = RedisKeys.rehold(pid, userA, seatId);
            assertThat(redis.opsForValue().get(key)).isEqualTo("1");
            assertThat(pttl(key)).isBetween(cooldown.toMillis() - TOLERANCE_MS, cooldown.toMillis());
        }
        assertThat(cleaner.keys(pid)).hasSize(2);
    }

    @Test
    @DisplayName("키에는 회차 해시 태그가 들어가고 좌석 ID를 키에서 되읽는다")
    void keysCarryHashTag() {
        assertThat(RedisKeys.seat(7, 42)).isEqualTo("seat:{p:7}:42");
        assertThat(RedisKeys.userHold(7, 3)).isEqualTo("userhold:{p:7}:3");
        assertThat(RedisKeys.rehold(7, 3, 42)).isEqualTo("rehold:{p:7}:3:42");
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
        for (String key : cleaner.keys(pid)) {
            values.put(key, redis.opsForValue().get(key));
        }
        return values;
    }

    private long pttl(String key) {
        return redis.getExpire(key, TimeUnit.MILLISECONDS);
    }

    private String seatKey(long seatId) {
        return RedisKeys.seat(pid, seatId);
    }

    private String userHoldKey(long userId) {
        return RedisKeys.userHold(pid, userId);
    }
}
