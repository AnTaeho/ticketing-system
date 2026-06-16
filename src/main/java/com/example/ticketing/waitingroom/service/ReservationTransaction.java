package com.example.ticketing.waitingroom.service;

import com.example.ticketing.global.stock.RedisStockRepository;
import com.example.ticketing.reservation.domain.Reservation;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTransaction {

    private final ReservationRepository reservationRepository;
    private final RedisStockRepository redisStockRepository;

    @Async("waitingRoomTaskExecutor")
    @Transactional
    public void saveReservationAsync(Long concertId, Long userId) {
        try {
            Reservation reservation = Reservation.of(concertId, userId, ReservationStatus.SUCCESS);
            reservationRepository.save(reservation);
            log.info("[WaitingRoom] DB 저장 완료 - concertId={}, userId={}", concertId, userId);
        } catch (Exception e) {
            log.error("[WaitingRoom] DB 저장 실패, 재고 복구 - concertId={}, userId={}", concertId, userId, e);
            redisStockRepository.increment(concertId);
        }
    }
}
