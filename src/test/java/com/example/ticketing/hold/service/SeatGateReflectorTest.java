package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SeatGateReflectorTest {

    private static final long PID = 9_100_000_000_001L;
    private static final GateHold HOLD = new GateHold(PID, "t", List.of(1L));

    @Autowired
    private TicketingProperties properties;
    @Autowired
    private TestRedis testRedis;

    @Test
    @DisplayName("Redis 장애면 해제 반영은 예외를 삼킨다")
    void swallowsRedisFailure() {
        SeatGateReflector reflector = reflector(TestServices.gateStore(testRedis.dead()), true);

        assertThatCode(() -> reflector.release(HOLD)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("게이트가 꺼져 있으면 해제 반영은 Redis를 부르지 않는다")
    void gateOffSkipsRedis() {
        SeatGateRedisStore store = mock(SeatGateRedisStore.class);
        SeatGateReflector reflector = reflector(store, false);

        reflector.release(HOLD);

        assertThat(reflector.enabled()).isFalse();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("게이트가 켜져 있으면 해제 반영은 좌석 키를 지우고, 쥔 좌석이 없으면 Redis를 부르지 않는다")
    void releasesOnlyWhenSeatsHeld() {
        SeatGateRedisStore store = mock(SeatGateRedisStore.class);
        SeatGateReflector reflector = reflector(store, true);
        GateHold noSeats = new GateHold(PID, "t2", List.of());

        reflector.release(HOLD);
        reflector.release(noSeats);

        verify(store).release(HOLD);
        verifyNoMoreInteractions(store);
    }

    private SeatGateReflector reflector(SeatGateRedisStore store, boolean gateEnabled) {
        return TestServices.gateReflector(store, TestProperties.from(properties).gateEnabled(gateEnabled).build());
    }
}
