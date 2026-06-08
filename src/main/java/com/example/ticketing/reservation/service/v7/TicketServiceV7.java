package com.example.ticketing.reservation.service.v7;

import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.stock.RedisStockRepository;
import com.example.ticketing.queue.service.QueueCommandService;
import com.example.ticketing.queue.repository.QueueRedisRepository;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV7 {

    private final QueueRedisRepository queueRepository;
    private final QueueCommandService queueCommandService;
    private final RedisStockRepository redisStockRepository;
    private final TicketTransactionV7 transaction;

    public ReserveResponse reserve(Long concertId, Long userId, String queueToken) {
        validateInProcessingQueue(concertId, queueToken);
        preemptStock(concertId, queueToken);
        transaction.saveReservationAsync(concertId, userId);
        log.info("[V7] 재고 선점 성공, 비동기 DB 처리 중 - concertId={}, userId={}", concertId, userId);
        return new ReserveResponse(null, ReservationStatus.SUCCESS);
    }

    private void validateInProcessingQueue(Long concertId, String queueToken) {
        if (!queueRepository.isInProcessingQueue(concertId, queueToken)) {
            throw new LockAcquisitionFailedException(concertId);
        }
    }

    private void preemptStock(Long concertId, String queueToken) {
        long remaining = redisStockRepository.decrement(concertId);
        queueCommandService.removeFromProcessing(concertId, queueToken);
        if (remaining < 0) {
            redisStockRepository.increment(concertId);
            throw new SoldOutException(concertId);
        }
        log.info("[V7] 재고 선점 - concertId={}, 남은재고={}", concertId, remaining);
    }
}
