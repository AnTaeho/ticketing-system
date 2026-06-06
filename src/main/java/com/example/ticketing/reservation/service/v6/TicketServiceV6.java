package com.example.ticketing.reservation.service.v6;

import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.stock.RedisStockRepository;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.kafka.TicketProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV6 {

    private final RedisStockRepository redisStockRepository;
    private final TicketProducer ticketProducer;

    public ReserveResponse reserve(Long concertId, Long userId) {
        long remaining = redisStockRepository.decrement(concertId);

        if (remaining < 0) {
            redisStockRepository.increment(concertId);
            log.info("[V6] 재고 선점 실패 (매진) - concertId={}, userId={}", concertId, userId);
            throw new SoldOutException(concertId);
        }

        log.info("[V6] 재고 선점 성공 - concertId={}, userId={}, 남은재고={}", concertId, userId, remaining);
        ticketProducer.publishReservationRequest(concertId, userId);
        return new ReserveResponse(null, ReservationStatus.SUCCESS);
    }
}
