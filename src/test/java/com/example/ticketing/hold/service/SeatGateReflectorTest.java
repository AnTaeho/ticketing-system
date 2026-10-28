package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.TestTicketingRedis;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SeatGateReflectorTest {

    private static final long PID = 9_100_000_000_001L;
    private static final GateHold HOLD = new GateHold(1L, PID, "t", List.of(1L));

    @Autowired
    private TicketingProperties props;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("Redis 장애면 반영은 예외를 삼키고 op별 실패 카운터를 올린다")
    void swallowsAndCounts() throws IOException {
        TicketingRedis deadRedis = TestTicketingRedis.connectTo("localhost", closedPort(), props);
        try {
            SeatGateReflector reflector = reflector(new SeatGateRedisStore(deadRedis), true);
            double release = failures("release");
            double extend = failures("extend");
            double confirm = failures("confirm");
            double cooldown = failures("cooldown");

            assertThatCode(() -> {
                reflector.release(HOLD);
                reflector.releaseSeatsOnly(HOLD);
                reflector.extend(HOLD, Duration.ofMinutes(11));
                reflector.confirm(HOLD, Instant.now().plusSeconds(3600));
                reflector.expire(HOLD);
            }).doesNotThrowAnyException();

            assertThat(failures("release") - release).isEqualTo(3);
            assertThat(failures("extend") - extend).isEqualTo(1);
            assertThat(failures("confirm") - confirm).isEqualTo(1);
            assertThat(failures("cooldown") - cooldown).isEqualTo(1);
        } finally {
            deadRedis.destroy();
        }
    }

    @Test
    @DisplayName("게이트가 꺼져 있으면 어떤 반영도 Redis를 부르지 않는다")
    void gateOffSkipsRedis() {
        SeatGateRedisStore store = mock(SeatGateRedisStore.class);
        SeatGateReflector reflector = reflector(store, false);

        reflector.release(HOLD);
        reflector.releaseSeatsOnly(HOLD);
        reflector.extend(HOLD, Duration.ofMinutes(11));
        reflector.confirm(HOLD, Instant.now());
        reflector.expire(HOLD);

        assertThat(reflector.enabled()).isFalse();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("만료 반영은 쿨다운을 건 뒤 키를 지우고, 쥔 좌석이 없으면 쿨다운 없이 지운다")
    void expireSetsCooldownBeforeRelease() {
        SeatGateRedisStore store = mock(SeatGateRedisStore.class);
        SeatGateReflector reflector = reflector(store, true);
        GateHold noSeats = new GateHold(2L, PID, "t2", List.of());

        reflector.expire(HOLD);
        reflector.expire(noSeats);

        InOrder order = inOrder(store);
        order.verify(store).setReholdCooldown(HOLD, props.hold().reholdCooldown());
        order.verify(store).release(HOLD);
        verify(store).release(noSeats);
        verify(store, never()).setReholdCooldown(eq(noSeats), any());
    }

    private SeatGateReflector reflector(SeatGateRedisStore store, boolean gateEnabled) {
        TicketingProperties p = props;
        TicketingProperties withGate = new TicketingProperties(p.seed(), p.redis(), p.hold(), p.payment(),
                new TicketingProperties.Gate(gateEnabled, p.gate().fallbackMaxConcurrency()), p.queue(), p.seatmap(),
                p.outbox(), p.ops(), p.pg(), p.fakePg());
        return new SeatGateReflector(store, withGate, meterRegistry, System::nanoTime);
    }

    private double failures(String op) {
        return meterRegistry.counter(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, op).count();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
