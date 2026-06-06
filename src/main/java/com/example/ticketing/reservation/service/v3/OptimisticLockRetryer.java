package com.example.ticketing.reservation.service.v3;

import com.example.ticketing.global.exception.ReservationFailedException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OptimisticLockRetryer {

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
                sleep();
            }
        }
    }

    private void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
