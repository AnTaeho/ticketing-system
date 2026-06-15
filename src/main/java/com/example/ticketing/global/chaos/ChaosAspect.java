package com.example.ticketing.global.chaos;

import io.lettuce.core.RedisCommandTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * V5CB 서킷브레이커 데모용 장애 주입.
 * Redis 차단 상태일 때 V5(Redisson) 예약 경로에서 {@link RedisCommandTimeoutException}을 던져
 * 서킷브레이커가 인프라 장애로 집계 → V2 폴백을 검증한다.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ChaosAspect {

    private final ChaosState chaosState;

    @Around("execution(* com.example.ticketing.reservation.service.v5.TicketServiceV5.reserve(..))")
    public Object applyRedissonChaos(ProceedingJoinPoint pjp) throws Throwable {
        if (chaosState.isRedisBlocked()) {
            log.debug("[ChaosAspect] Redis 차단 주입 - V5 reserve");
            throw new RedisCommandTimeoutException("Chaos: Redis 차단됨");
        }
        return pjp.proceed();
    }
}
