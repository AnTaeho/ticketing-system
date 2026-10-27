package com.example.ticketing.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.hold.HoldExpiryTransaction.ExpiredHold;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HoldExpirySchedulerTest {

    private static final int BATCH = 3;

    private HoldExpiryTransaction expiryTransaction;
    private SimpleMeterRegistry meterRegistry;
    private HoldExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        expiryTransaction = mock(HoldExpiryTransaction.class);
        meterRegistry = new SimpleMeterRegistry();
        BookingProperties properties = new BookingProperties(null, null,
                new BookingProperties.Hold(Duration.ofMinutes(5), 4, 1000, BATCH, Duration.ofMinutes(10)),
                null, null, null, null, null, null, null, null);
        scheduler = new HoldExpiryScheduler(expiryTransaction, properties, meterRegistry);
    }

    @Test
    @DisplayName("배치가 가득 차면 같은 주기에서 다시 돌고, 덜 차면 멈춘다")
    @SuppressWarnings("unchecked")
    void repeatsWhileFull() {
        when(expiryTransaction.claimAndExpire(BATCH)).thenReturn(holds(BATCH), holds(BATCH), holds(1));

        scheduler.scan();

        verify(expiryTransaction, times(3)).claimAndExpire(BATCH);
        assertThat(expiredCount()).isEqualTo(BATCH * 2 + 1);
    }

    @Test
    @DisplayName("계속 가득 차도 한 주기에 최대 10번까지만 돈다")
    void stopsAtMaxRounds() {
        when(expiryTransaction.claimAndExpire(BATCH)).thenReturn(holds(BATCH));

        scheduler.scan();

        verify(expiryTransaction, times(HoldExpiryScheduler.MAX_ROUNDS)).claimAndExpire(BATCH);
        assertThat(expiredCount()).isEqualTo(BATCH * HoldExpiryScheduler.MAX_ROUNDS);
    }

    @Test
    @DisplayName("만료할 것이 없으면 한 번만 확인한다")
    void emptyRunsOnce() {
        when(expiryTransaction.claimAndExpire(BATCH)).thenReturn(List.of());

        scheduler.scan();

        verify(expiryTransaction, times(1)).claimAndExpire(BATCH);
        assertThat(expiredCount()).isZero();
    }

    private double expiredCount() {
        return meterRegistry.counter("booking.hold.expired").count();
    }

    private static List<ExpiredHold> holds(int count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(id -> new ExpiredHold(id, 1L, id, "token-" + id, List.of(id)))
                .toList();
    }
}
