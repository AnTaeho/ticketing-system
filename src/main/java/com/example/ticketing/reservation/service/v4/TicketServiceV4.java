package com.example.ticketing.reservation.service.v4;

import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.lock.LettuceLockRepository;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV4 implements TicketService {

    private static final long SPIN_WAIT_MS = 100;
    private static final int MAX_SPIN_COUNT = 100;

    private final LettuceLockRepository lettuceLockRepository;
    private final TicketTransactionV4 transaction;

    @Override
    public ReserveResponse reserve(Long concertId, Long userId) {
        acquireSpinLock(concertId);
        try {
            return transaction.reserveInTransaction(concertId, userId);
        } finally {
            lettuceLockRepository.releaseLock(concertId);
            log.info("[V4] 락 해제 - concertId={}", concertId);
        }
    }

    private void acquireSpinLock(Long concertId) {
        int spinCount = 0;
        while (!lettuceLockRepository.tryLock(concertId)) {
            spinCount++;
            log.info("[V4] 락 대기 중 - concertId={}, 대기횟수={}", concertId, spinCount);
            if (spinCount >= MAX_SPIN_COUNT) {
                throw new LockAcquisitionFailedException(concertId);
            }
            sleep();
        }
        log.info("[V4] 락 획득 성공 - concertId={}", concertId);
    }

    private void sleep() {
        try {
            Thread.sleep(SPIN_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
