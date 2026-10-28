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
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
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
    private TicketingProperties properties;
    @Autowired
    private TestServices services;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private Counters counters;

    @Test
    @DisplayName("Redis 장애면 반영은 예외를 삼키고 작업별 실패를 센다")
    void swallowsAndCounts() {
        SeatGateReflector reflector = reflector(TestServices.gateStore(testRedis.dead()), true);
        double release = counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "release");
        double extend = counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "extend");
        double confirm = counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "confirm");
        double cooldown = counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "cooldown");

        assertThatCode(() -> {
            reflector.release(HOLD);
            reflector.releaseSeatsOnly(HOLD);
            reflector.extend(HOLD, Duration.ofMinutes(11));
            reflector.confirm(HOLD, Instant.now().plusSeconds(3600));
            reflector.expire(HOLD);
        }).doesNotThrowAnyException();

        assertThat(counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "release") - release).isEqualTo(3);
        assertThat(counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "extend") - extend).isEqualTo(1);
        assertThat(counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "confirm") - confirm).isEqualTo(1);
        assertThat(counters.count(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, "cooldown") - cooldown).isEqualTo(1);
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
        order.verify(store).setReholdCooldown(HOLD, properties.hold().reholdCooldown());
        order.verify(store).release(HOLD);
        verify(store).release(noSeats);
        verify(store, never()).setReholdCooldown(eq(noSeats), any());
    }

    private SeatGateReflector reflector(SeatGateRedisStore store, boolean gateEnabled) {
        return services.gateReflector(store, TestProperties.from(properties).gateEnabled(gateEnabled).build());
    }
}
