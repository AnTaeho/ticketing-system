package com.example.ticketing.hold.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestRedis;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private long seat1;
    private long seat2;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        redis = testRedis.template();
        pid = fixtures.redisOnlyPerformanceId();
        seat1 = ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
        seat2 = seat1 + 1;
        seats = List.of(seat1, seat2);
    }

    @Test
    @DisplayName("선점에 성공하면 좌석 키에 토큰을 쓰고 수명을 건다")
    void holdWritesKeys() {
        Optional<Long> result = seatGateRedisStore.tryHold(new GateHold(pid, "tA", seats), TTL);

        assertThat(result).isEmpty();
        for (String key : List.of(seatKey(seat1), seatKey(seat2))) {
            assertThat(redis.opsForValue().get(key)).isEqualTo("tA");
            assertThat(testRedis.pttl(key)).isBetween(TTL.toMillis() - TOLERANCE_MS, TTL.toMillis());
        }
        assertThat(testRedis.keys(pid)).hasSize(2);
    }

    @Test
    @DisplayName("잡힌 좌석이 섞이면 SEAT_TAKEN이고 키를 쓰지 않는다")
    void seatTakenWritesNothing() {
        seatGateRedisStore.tryHold(new GateHold(pid, "tB", List.of(seat2)), TTL);
        Map<String, String> before = snapshot();

        Optional<Long> result = seatGateRedisStore.tryHold(new GateHold(pid, "tA", seats), TTL);

        assertThat(result).contains(seat2);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(redis.hasKey(seatKey(seat1))).isFalse();
    }

    @Test
    @DisplayName("해제(release)는 토큰이 같은 키만 지우고 남의 키는 남긴다")
    void releaseOnlyOwnKeys() {
        seatGateRedisStore.tryHold(new GateHold(pid, "tA", seats), TTL);
        redis.opsForValue().set(seatKey(seat2), "tOther", TTL);

        long deleted = seatGateRedisStore.release(new GateHold(pid, "tA", seats));

        assertThat(deleted).isEqualTo(1);
        assertThat(redis.hasKey(seatKey(seat1))).isFalse();
        assertThat(redis.opsForValue().get(seatKey(seat2))).isEqualTo("tOther");
    }

    @Test
    @DisplayName("키에는 회차 해시 태그가 들어가고 좌석 ID를 키에서 되읽는다")
    void keysCarryHashTag() {
        assertThat(RedisKeys.seat(7, 42)).isEqualTo("seat:{p:7}:42");
        assertThat(RedisKeys.seatIdOf("seat:{p:7}:42")).isEqualTo(42);
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
}
