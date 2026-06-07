package com.example.ticketing.global.chaos;

import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ChaosAspect {

    private final ChaosState chaosState;
    private final MeterRegistry meterRegistry;

    // V4 LettuceLock + V6 RedisStock
    @Around("execution(* com.example.ticketing.global.lock.LettuceLockRepository.*(..)) " +
            "|| execution(* com.example.ticketing.global.stock.RedisStockRepository.*(..))")
    public Object applyLettuceRedisChaos(ProceedingJoinPoint pjp) throws Throwable {
        return applyChaos(pjp, "lettuce");
    }

    // V5 Redisson — reserve() 진입 시점에서 Redis 장애 시뮬레이션
    @Around("execution(* com.example.ticketing.reservation.service.v5.TicketServiceV5.reserve(..))")
    public Object applyRedissonChaos(ProceedingJoinPoint pjp) throws Throwable {
        return applyChaos(pjp, "redisson");
    }

    private Object applyChaos(ProceedingJoinPoint pjp, String target) throws Throwable {
        ChaosState.RedisMode mode = chaosState.getRedisMode();
        long start = System.currentTimeMillis();
        try {
            switch (mode) {
                case DELAY:
                    log.debug("[ChaosAspect] Redis 지연 주입 - target={}, delayMs={}", target, chaosState.getRedisDelayMs());
                    Thread.sleep(chaosState.getRedisDelayMs());
                    return pjp.proceed();
                case BLOCK:
                    log.debug("[ChaosAspect] Redis 차단 주입 - target={}", target);
                    throw new RedisCommandTimeoutException("Chaos: Redis 차단됨");
                default:
                    return pjp.proceed();
            }
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            meterRegistry.timer("chaos.redis.call.duration",
                    "target", target,
                    "chaosMode", mode.name()
            ).record(elapsed, TimeUnit.MILLISECONDS);
        }
    }
}
