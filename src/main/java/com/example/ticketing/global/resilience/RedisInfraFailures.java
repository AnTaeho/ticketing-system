package com.example.ticketing.global.resilience;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;

/**
 * Redis 인프라 장애(연결 끊김/타임아웃) 판별 유틸.
 *
 * <p>Circuit Breaker의 {@code recordException} 기준과 V5CB 폴백 분기 기준이
 * 동일해야 하므로 단일 진실 공급원으로 둔다. (중첩 cause를 순회해 래핑된 예외도 탐지)
 */
public final class RedisInfraFailures {

    private RedisInfraFailures() {
    }

    public static boolean isInfraFailure(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof RedisCommandTimeoutException ||
                cause instanceof RedisConnectionException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
