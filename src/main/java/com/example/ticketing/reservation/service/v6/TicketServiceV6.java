package com.example.ticketing.reservation.service.v6;

import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.stock.RedisStockRepository;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.kafka.ReservationMessage;
import com.example.ticketing.reservation.outbox.OutboxCreatedEvent;
import com.example.ticketing.reservation.outbox.OutboxEvent;
import com.example.ticketing.reservation.outbox.OutboxEventRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceV6 {

    private final RedisStockRepository redisStockRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReserveResponse reserve(Long concertId, Long userId) {
        long remaining = redisStockRepository.decrement(concertId);

        if (remaining < 0) {
            redisStockRepository.increment(concertId);
            log.info("[V6] 재고 선점 실패 (매진) - concertId={}, userId={}", concertId, userId);
            throw new SoldOutException(concertId);
        }

        String ticketToken = UUID.randomUUID().toString();
        String payload = serializeMessage(concertId, userId, ticketToken);

        OutboxEvent outboxEvent = OutboxEvent.create(concertId, ticketToken, payload);
        outboxEventRepository.save(outboxEvent);

        eventPublisher.publishEvent(new OutboxCreatedEvent(outboxEvent.getId()));

        log.info("[V6] 재고 선점 성공, Outbox 저장 - concertId={}, userId={}, 남은재고={}", concertId, userId, remaining);
        return new ReserveResponse(null, ReservationStatus.PENDING, ticketToken);
    }

    private String serializeMessage(Long concertId, Long userId, String ticketToken) {
        try {
            return objectMapper.writeValueAsString(new ReservationMessage(concertId, userId, ticketToken));
        } catch (JacksonException e) {
            throw new RuntimeException("Outbox 메시지 직렬화 실패", e);
        }
    }
}
