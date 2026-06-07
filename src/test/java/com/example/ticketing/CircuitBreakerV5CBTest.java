package com.example.ticketing;

import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.resilience.CircuitBreakerStatsHolder;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.service.v2.TicketServiceV2;
import com.example.ticketing.reservation.service.v5.TicketServiceV5;
import com.example.ticketing.reservation.service.v5cb.TicketServiceV5CB;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerV5CBTest {

    @Mock TicketServiceV5 mockV5;
    @Mock TicketServiceV2 mockV2;

    CircuitBreakerStatsHolder statsHolder;
    CircuitBreaker cb;
    TicketServiceV5CB service;

    @BeforeEach
    void setUp() {
        statsHolder = new CircuitBreakerStatsHolder();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(60.0f)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(e -> e instanceof RedisCommandTimeoutException)
                .build();
        cb = CircuitBreakerRegistry.of(config).circuitBreaker("test-cb");
        service = new TicketServiceV5CB(mockV5, mockV2, cb, statsHolder);
    }

    @Test
    @DisplayName("Redis 3회 실패 후 CB OPEN — 이후 요청은 V2 폴백으로 처리")
    void redis_장애_3회_후_cb_open_v2_폴백() {
        ReserveResponse v2Response = new ReserveResponse(99L, ReservationStatus.SUCCESS);
        when(mockV5.reserve(anyLong(), anyLong()))
                .thenThrow(new RedisCommandTimeoutException("chaos: Redis 차단"));
        when(mockV2.reserve(anyLong(), anyLong())).thenReturn(v2Response);

        for (int i = 0; i < 5; i++) {
            service.reserve(1L, (long) i);
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(statsHolder.getFallbackPathCount()).isEqualTo(5);
        assertThat(statsHolder.getRedisPathCount()).isEqualTo(0);
        verify(mockV2, times(5)).reserve(anyLong(), anyLong());
    }

    @Test
    @DisplayName("LockAcquisitionFailedException은 폴백 없이 전파")
    void lock_경합_예외는_폴백_없이_전파() {
        when(mockV5.reserve(anyLong(), anyLong()))
                .thenThrow(new LockAcquisitionFailedException(1L));

        assertThatThrownBy(() -> service.reserve(1L, 1L))
                .isInstanceOf(LockAcquisitionFailedException.class);
        verify(mockV2, never()).reserve(anyLong(), anyLong());
        assertThat(statsHolder.getFallbackPathCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Redis 경로 성공 시 redisPathCount 증가")
    void redis_경로_성공시_stats_증가() {
        ReserveResponse v5Response = new ReserveResponse(1L, ReservationStatus.SUCCESS);
        when(mockV5.reserve(anyLong(), anyLong())).thenReturn(v5Response);

        service.reserve(1L, 1L);

        assertThat(statsHolder.getRedisPathCount()).isEqualTo(1);
        assertThat(statsHolder.getFallbackPathCount()).isEqualTo(0);
    }
}
