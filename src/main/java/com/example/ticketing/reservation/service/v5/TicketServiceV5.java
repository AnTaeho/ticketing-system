package com.example.ticketing.reservation.service;

import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.reservation.dto.ReserveResponse;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV5 implements TicketService {

    private static final long LOCK_WAIT_SECONDS  = 5;
    private static final long LOCK_LEASE_SECONDS = 3;
    private static final String LOCK_KEY_PREFIX  = "lock:concert:";

    private final RedissonClient redissonClient;
    private final TicketTransactionV5 transaction;

    @Override
    public ReserveResponse reserve(Long concertId, Long userId) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        acquireRedissonLock(lock, concertId);
        try {
            return transaction.reserveInTransaction(concertId, userId);
        } finally {
            releaseLockSafely(lock, concertId);
        }
    }

    private void acquireRedissonLock(RLock lock, Long concertId) {
        try {
            boolean acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LockAcquisitionFailedException(concertId);
            }
            log.info("[V5] 락 획득 성공 - concertId={}", concertId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionFailedException(concertId);
        }
    }

    private void releaseLockSafely(RLock lock, Long concertId) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("[V5] 락 해제 - concertId={}", concertId);
        }
    }
}
