package com.example.ticketing.reservation.service.v3;

import com.example.ticketing.global.exception.ReservationFailedException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OptimisticLockRetryer {

    private static final long BASE_WAIT_MS = 50;
    private static final long MAX_WAIT_MS  = 1_000;

    public <T> T executeWithRetry(Supplier<T> action, Long concertId, int maxRetry) {
        int retryCount = 0;
        while (true) {
            try {
                return action.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                retryCount++;
                log.info("[V3] 낙관적 락 충돌, 재시도 - concertId={}, 시도횟수={}", concertId, retryCount);
                if (retryCount >= maxRetry) {
                    throw new ReservationFailedException(concertId);
                }
                sleepWithBackoff(retryCount);
            }
        }
    }

    private void sleepWithBackoff(int retryCount) {
        long backoffMs = BASE_WAIT_MS * (1L << retryCount);
        long cappedMs  = Math.min(backoffMs, MAX_WAIT_MS);
        long jitteredMs = (long) (Math.random() * cappedMs);
        try {
            Thread.sleep(jitteredMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
