package com.example.ticketing.reservation.service.v5cb;

import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.resilience.CircuitBreakerStatsHolder;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.service.TicketService;
import com.example.ticketing.reservation.service.v2.TicketServiceV2;
import com.example.ticketing.reservation.service.v5.TicketServiceV5;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV5CB implements TicketService {

    private final TicketServiceV5 ticketServiceV5;
    private final TicketServiceV2 ticketServiceV2;
    private final CircuitBreaker  redisLockCircuitBreaker;
    private final CircuitBreakerStatsHolder statsHolder;

    @Override
    public ReserveResponse reserve(Long concertId, Long userId) {
        try {
            ReserveResponse response = redisLockCircuitBreaker.executeCheckedSupplier(
                    () -> ticketServiceV5.reserve(concertId, userId)
            );
            statsHolder.incrementRedisPath();
            log.info("[V5CB] Redis 경로 성공 - concertId={}, cbState={}",
                    concertId, redisLockCircuitBreaker.getState());
            return response;
        } catch (Throwable e) {
            if (e instanceof LockAcquisitionFailedException lafe) throw lafe;
            if (e instanceof SoldOutException soe)              throw soe;
            log.warn("[V5CB] Circuit fallback 진입 - concertId={}, cbState={}, cause={}",
                    concertId, redisLockCircuitBreaker.getState(), e.getClass().getSimpleName());
            statsHolder.incrementFallbackPath();
            return ticketServiceV2.reserve(concertId, userId);
        }
    }
}
