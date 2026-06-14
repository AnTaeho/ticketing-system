package com.example.ticketing.reservation.kafka;

import com.example.ticketing.reservation.domain.Reservation;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketConsumer {

    private final ReservationRepository reservationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = "${ticketing.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}", concurrency = "1")
    public void consumeReservationRequest(String payload, Acknowledgment ack) {
        ReservationMessage message = deserialize(payload);
        log.info("[V6] 메시지 수신 - concertId={}, userId={}, ticketToken={}", message.concertId(), message.userId(), message.ticketToken());

        if (reservationRepository.existsByTicketToken(message.ticketToken())) {
            log.warn("[V6] 중복 메시지 스킵 - ticketToken={}", message.ticketToken());
            ack.acknowledge();
            return;
        }

        saveReservation(message.concertId(), message.userId(), message.ticketToken());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ack.acknowledge();
            }
        });
    }

    private void saveReservation(Long concertId, Long userId, String ticketToken) {
        // 재고 차감은 V6 진입 시 Redis DECR로 이미 원자적으로 선점됨(SSOT=Redis).
        // 컨슈머는 재고를 재검증하지 않고 예약 기록만 영속화한다(V7과 동일 모델).
        Reservation reservation = Reservation.ofV6(concertId, userId, ReservationStatus.SUCCESS, ticketToken);
        reservationRepository.save(reservation);
        log.info("[V6] DB 저장 완료 - reservationId={}, concertId={}, userId={}",
                reservation.getId(), concertId, userId);
    }

    private ReservationMessage deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, ReservationMessage.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Kafka 메시지 역직렬화 실패: " + payload, e);
        }
    }
}
