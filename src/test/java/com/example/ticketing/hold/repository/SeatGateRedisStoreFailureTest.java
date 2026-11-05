package com.example.ticketing.hold.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

// 닫힌 포트로 만든 예매 연결로 Redis 장애를 재현한다
@IntegrationTest
class SeatGateRedisStoreFailureTest {

    private static final long PID = 9_100_000_000_000L;
    private static final GateHold HOLD = new GateHold(PID, "t", List.of(1L, 2L));

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
    @DisplayName("해제(release) 중 Redis가 죽어도 같은 예외로 바꿔 던진다")
    void releaseThrowsTranslated() {
        assertThatThrownBy(() -> deadGate.release(HOLD))
                .isInstanceOf(RedisUnavailableException.class)
                .hasCauseInstanceOf(DataAccessException.class);
    }
}
