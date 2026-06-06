package com.example.ticketing.reservation.service;

import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.reservation.domain.Reservation;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.global.exception.ConcertNotFoundException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV2 implements TicketService {

    private final ConcertRepository concertRepository;
    private final ReservationRepository reservationRepository;

    @Override
    @Transactional(timeout = 5)
    public ReserveResponse reserve(Long concertId, Long userId) {
        Concert concert = findConcertWithPessimisticLock(concertId);
        validateStockAvailable(concert);
        decreaseStock(concert);
        Reservation reservation = saveReservation(concertId, userId);
        return new ReserveResponse(reservation.getId(), reservation.getStatus());
    }

    private Concert findConcertWithPessimisticLock(Long concertId) {
        return concertRepository.findByIdWithPessimisticLock(concertId)
                .orElseThrow(() -> new ConcertNotFoundException(concertId));
    }

    private void validateStockAvailable(Concert concert) {
        if (concert.isOutOfStock()) {
            throw new SoldOutException(concert.getId());
        }
    }

    private void decreaseStock(Concert concert) {
        concert.decrease();
        log.info("[V2] 재고 차감 - concertId={}, 차감 후 재고={}", concert.getId(), concert.getStock());
    }

    private Reservation saveReservation(Long concertId, Long userId) {
        Reservation reservation = Reservation.of(concertId, userId, ReservationStatus.SUCCESS);
        reservationRepository.save(reservation);
        log.info("[V2] 예약 완료 - reservationId={}, userId={}", reservation.getId(), userId);
        return reservation;
    }
}
