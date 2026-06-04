package com.example.ticketing.reservation.service;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.ConcertNotFoundException;
import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.lock.LettuceLockRepository;
import com.example.ticketing.reservation.domain.Reservation;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV4 implements TicketService {

    private static final long SPIN_WAIT_MS = 100;
    private static final int MAX_SPIN_COUNT = 100;

    private final ConcertRepository concertRepository;
    private final ReservationRepository reservationRepository;
    private final LettuceLockRepository lettuceLockRepository;

    // 자기 자신의 프록시를 주입받아 @Transactional이 적용된 메서드를 호출하기 위함
    @Lazy
    @Autowired
    private TicketServiceV4 self;

    // @Transactional 없음 — 트랜잭션 커밋 이후 락 해제를 보장하기 위해
    @Override
    public ReserveResponse reserve(Long concertId, Long userId) {
        acquireSpinLock(concertId);
        try {
            return self.reserveInTransaction(concertId, userId);
        } finally {
            lettuceLockRepository.releaseLock(concertId);
            log.info("[V4] 락 해제 - concertId={}", concertId);
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
        log.info("[V4] 재고 차감 - concertId={}, 차감 후 재고={}", concert.getId(), concert.getStock());
    }

    private Reservation saveReservation(Long concertId, Long userId) {
        Reservation reservation = Reservation.of(concertId, userId, ReservationStatus.SUCCESS);
        reservationRepository.save(reservation);
        log.info("[V4] 예약 완료 - reservationId={}, userId={}", reservation.getId(), userId);
        return reservation;
    }

    private void sleep() {
        try {
            Thread.sleep(SPIN_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
