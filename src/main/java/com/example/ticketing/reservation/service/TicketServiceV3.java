package com.example.ticketing.reservation.service;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.global.exception.ConcertNotFoundException;
import com.example.ticketing.global.exception.SoldOutException;
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
public class TicketServiceV3 implements TicketService {

    private static final int MAX_RETRY = 10;

    private final ConcertRepository concertRepository;
    private final ReservationRepository reservationRepository;
    private final OptimisticLockRetryer retryer;

    // 자기 자신의 프록시를 주입받아 @Transactional이 적용된 메서드를 호출하기 위함
    @Lazy
    @Autowired
    private TicketServiceV3 self;

    @Override
    public ReserveResponse reserve(Long concertId, Long userId) {
        return retryer.executeWithRetry(
                () -> self.reserveInTransaction(concertId, userId),
                concertId,
                MAX_RETRY
        );
    }

    @Transactional
    public ReserveResponse reserveInTransaction(Long concertId, Long userId) {
        Concert concert = findConcertById(concertId);
        validateStockAvailable(concert);
        decreaseStock(concert);
        Reservation reservation = saveReservation(concertId, userId);
        return new ReserveResponse(reservation.getId(), reservation.getStatus());
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
        log.info("[V3] 재고 차감 - concertId={}, 차감 후 재고={}", concert.getId(), concert.getStock());
    }

    private Reservation saveReservation(Long concertId, Long userId) {
        Reservation reservation = Reservation.of(concertId, userId, ReservationStatus.SUCCESS);
        reservationRepository.save(reservation);
        log.info("[V3] 예약 완료 - reservationId={}, userId={}", reservation.getId(), userId);
        return reservation;
    }
}
