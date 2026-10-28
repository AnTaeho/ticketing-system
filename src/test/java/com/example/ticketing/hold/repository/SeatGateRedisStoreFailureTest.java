package com.example.ticketing.hold.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

// 닫힌 포트로 만든 예매 연결로 Redis 장애를 재현한다
@IntegrationTest
class SeatGateRedisStoreFailureTest {

    private static final long PID = 9_100_000_000_000L;
    private static final GateHold HOLD = new GateHold(1L, PID, "t", List.of(1L, 2L));

    @Autowired
    private TestRedis testRedis;

    private SeatGateRedisStore deadGate;

    @BeforeEach
    void setUp() {
        deadGate = TestServices.gateStore(testRedis.dead());
    }

    @Test
    @DisplayName("선점(tryHold) 중 Redis가 죽으면 원인을 담은 RedisUnavailableException을 던진다")
    void tryHoldThrows() {
        assertThatThrownBy(() -> deadGate.tryHold(HOLD, Duration.ofMinutes(5)))
                .isInstanceOf(RedisUnavailableException.class)
                .hasCauseInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("해제·연장·확정·쿨다운 중 Redis가 죽어도 같은 예외로 바꿔 던진다")
    void reflectionsThrowTranslated() {
        List<ThrowingCallable> calls = List.of(
                () -> deadGate.release(HOLD),
                () -> deadGate.releaseSeatsOnly(HOLD),
                () -> deadGate.extend(HOLD, Duration.ofMinutes(11)),
                () -> deadGate.confirm(HOLD, Instant.now().plusSeconds(3600)),
                () -> deadGate.setReholdCooldown(HOLD, Duration.ofMinutes(10)));

        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call)
                    .isInstanceOf(RedisUnavailableException.class)
                    .hasCauseInstanceOf(DataAccessException.class);
        }
    }
}
