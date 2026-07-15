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
            // flush까지 이 메서드 안에서 수행해야 제약조건/SQL 오류를 잡아 Redis 재고를 보상할 수 있다.
            reservationRepository.saveAndFlush(reservation);
            log.info("[WaitingRoom] DB 저장 완료 - concertId={}, userId={}", concertId, userId);
        } catch (Exception e) {
            log.error("[WaitingRoom] DB 저장 실패, 재고 복구 - concertId={}, userId={}", concertId, userId, e);
            redisStockRepository.increment(concertId);
        }
    }
}
