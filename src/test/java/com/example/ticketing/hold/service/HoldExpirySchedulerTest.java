package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.config.TicketingProperties.Hold;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.dto.HoldExpiry;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestServices;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 쿨다운·게이트 스위치 같은 반영 규칙은 SeatGateReflectorTest가 본다. 여기서는 반영을 넘기는지만 본다
class HoldExpirySchedulerTest {

    private static final int BATCH = 3;

    private HoldExpiryTransaction holdExpiryTransaction;
    private SeatGateReflector gateReflector;
    private Counters counters;
    private HoldExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        holdExpiryTransaction = mock(HoldExpiryTransaction.class);
        gateReflector = mock(SeatGateReflector.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        counters = new Counters(meterRegistry);
        TicketingProperties properties = TestProperties.empty()
                .hold(new Hold(Duration.ofMinutes(5), 4, true, 1000, BATCH, Duration.ofMinutes(10)))
                .build();
        scheduler = TestServices.holdExpiryScheduler(holdExpiryTransaction, gateReflector, meterRegistry, properties);
    }

    @Test
    @DisplayName("만료가 커밋된 선점마다 게이트 반영을 넘기고 만료 수를 센다")
    void expiresGateOfEachHold() {
        GateHold withSeats = new GateHold(11L, 7L, "token-1", List.of(101L, 102L));
        GateHold seatsTaken = new GateHold(12L, 7L, "token-2", List.of());
        when(holdExpiryTransaction.claimAndExpire(BATCH))
                .thenReturn(List.of(new HoldExpiry(1L, withSeats), new HoldExpiry(2L, seatsTaken)));

        scheduler.run();

        verify(gateReflector).expire(withSeats);
        verify(gateReflector).expire(seatsTaken);
        assertThat(counters.count(Metrics.HOLD_EXPIRED)).isEqualTo(2);
    }

    @Test
    @DisplayName("배치가 가득 차면 같은 주기에서 다시 돌고 덜 차면 멈춘다")
    @SuppressWarnings("unchecked")
    void repeatsWhileFull() {
        when(holdExpiryTransaction.claimAndExpire(BATCH)).thenReturn(holds(BATCH), holds(BATCH), holds(1));

        scheduler.run();

        verify(holdExpiryTransaction, times(3)).claimAndExpire(BATCH);
        assertThat(counters.count(Metrics.HOLD_EXPIRED)).isEqualTo(BATCH * 2 + 1);
    }

    @Test
    @DisplayName("계속 가득 차도 한 주기에 최대 10번까지만 돈다")
    void stopsAtMaxRounds() {
        when(holdExpiryTransaction.claimAndExpire(BATCH)).thenReturn(holds(BATCH));

        scheduler.run();

        verify(holdExpiryTransaction, times(HoldExpiryScheduler.MAX_ROUNDS)).claimAndExpire(BATCH);
        assertThat(counters.count(Metrics.HOLD_EXPIRED)).isEqualTo(BATCH * HoldExpiryScheduler.MAX_ROUNDS);
    }

    @Test
    @DisplayName("만료할 것이 없으면 한 번만 확인한다")
    void emptyRunsOnce() {
        when(holdExpiryTransaction.claimAndExpire(BATCH)).thenReturn(List.of());

        scheduler.run();

        verify(holdExpiryTransaction, times(1)).claimAndExpire(BATCH);
        assertThat(counters.count(Metrics.HOLD_EXPIRED)).isZero();
    }

    private static List<HoldExpiry> holds(int count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(id -> new HoldExpiry(id, new GateHold(id, 1L, "token-" + id, List.of(id))))
                .toList();
    }
}
