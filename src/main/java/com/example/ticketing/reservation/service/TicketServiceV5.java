package com.example.ticketing.reservation.service;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.ConcertNotFoundException;
import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.reservation.domain.Reservation;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV5 implements TicketService {

    private static final long LOCK_WAIT_SECONDS  = 5;
    private static final long LOCK_LEASE_SECONDS = 3;
    private static final String LOCK_KEY_PREFIX  = "lock:concert:";

    private final ConcertRepository concertRepository;
    private final ReservationRepository reservationRepository;
    private final RedissonClient redissonClient;

    @Lazy
    @Autowired
    private TicketServiceV5 self;

    @Override
    public ReserveResponse reserve(Long concertId, Long userId) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + concertId);
        acquireRedissonLock(lock, concertId);
        try {
            return self.reserveInTransaction(concertId, userId);
        } finally {
            releaseLockSafely(lock, concertId);
        }
    }

    @Transactional
    public ReserveResponse reserveInTransaction(Long concertId, Long userId) {
        Concert concert = findConcertById(concertId);
        validateStockAvailable(concert);
        decreaseStock(concert);
        Reservation reservation = saveReservation(concertId, userId);
        return new ReserveResponse(reservation.getId(), reservation.getStatus());
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

    private Concert findConcertById(Long concertId) {
        return concertRepository.findById(concertId)
                .orElseThrow(() -> new ConcertNotFoundException(concertId));
    }

    private void validateStockAvailable(Concert concert) {
        if (concert.isOutOfStock()) {
            throw new SoldOutException(concert.getId());
        }
    }

    private void decreaseStock(Concert concert) {
        concert.decrease();
        log.info("[V5] 재고 차감 - concertId={}, 차감 후 재고={}", concert.getId(), concert.getStock());
    }

    private Reservation saveReservation(Long concertId, Long userId) {
        Reservation reservation = Reservation.of(concertId, userId, ReservationStatus.SUCCESS);
        reservationRepository.save(reservation);
        log.info("[V5] 예약 완료 - reservationId={}, userId={}", reservation.getId(), userId);
        return reservation;
    }
}
