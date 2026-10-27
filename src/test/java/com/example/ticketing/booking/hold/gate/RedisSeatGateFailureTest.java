package com.example.ticketing.booking.hold.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingRedis;
import com.example.ticketing.booking.common.TestBookingRedis;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

// 닫힌 포트로 만든 예매 연결로 Redis 장애를 재현한다
@BookingIntegrationTest
class RedisSeatGateFailureTest {

    private static final long PID = 9_100_000_000_000L;

    @Autowired
    private BookingProperties props;
    @Autowired
    private MeterRegistry meterRegistry;

    private BookingRedis deadRedis;
    private RedisSeatGate deadGate;

    @BeforeEach
    void setUp() throws IOException {
        deadRedis = TestBookingRedis.connectTo("localhost", closedPort(), props);
        deadGate = new RedisSeatGate(deadRedis, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        deadRedis.destroy();
    }

    @Test
    @DisplayName("tryHold는 Redis 예외를 그대로 던진다")
    void tryHoldThrows() {
        assertThatThrownBy(() -> deadGate.tryHold(PID, 1L, List.of(1L, 2L), "t", Duration.ofMinutes(5)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("Quietly 변형은 Redis 예외를 삼키고 op별 실패 카운터를 올린다")
    void quietlySwallowsAndCounts() {
        double release = failures("release");
        double extend = failures("extend");
        double confirm = failures("confirm");
        double cooldown = failures("cooldown");

        assertThatCode(() -> {
            deadGate.releaseQuietly(PID, 1L, List.of(1L), "t");
            deadGate.releaseSeatsOnlyQuietly(PID, List.of(1L), "t");
            deadGate.extendQuietly(PID, 1L, List.of(1L), "t", Duration.ofMinutes(11));
            deadGate.confirmQuietly(PID, 1L, List.of(1L), "t", Instant.now().plusSeconds(3600));
            deadGate.setReholdCooldownQuietly(PID, 1L, List.of(1L), Duration.ofMinutes(10));
        }).doesNotThrowAnyException();

        assertThat(failures("release") - release).isEqualTo(2);
        assertThat(failures("extend") - extend).isEqualTo(1);
        assertThat(failures("confirm") - confirm).isEqualTo(1);
        assertThat(failures("cooldown") - cooldown).isEqualTo(1);
    }

    private double failures(String op) {
        return meterRegistry.counter("booking.redis.reflect.failure", "op", op).count();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
