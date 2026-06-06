package com.example.ticketing.reservation.kafka;

import com.example.ticketing.concert.domain.Concert;
import com.example.ticketing.concert.repository.ConcertRepository;
import com.example.ticketing.reservation.domain.Reservation;
import com.example.ticketing.reservation.domain.ReservationStatus;
import com.example.ticketing.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketConsumer {

    private final ConcertRepository concertRepository;
    private final ReservationRepository reservationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @KafkaListener(topics = "${ticketing.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}", concurrency = "1")
    public void consumeReservationRequest(String payload) {
        ReservationMessage message = deserialize(payload);
        log.info("[V6] 메시지 수신 - concertId={}, userId={}", message.concertId(), message.userId());
        saveReservation(message.concertId(), message.userId());
    }

    private void saveReservation(Long concertId, Long userId) {
        Concert concert = concertRepository.findById(concertId).orElse(null);
        if (concert == null) {
            log.warn("[V6] 콘서트 없음 - concertId={}", concertId);
            return;
        }
        concert.decrease();
        Reservation reservation = Reservation.of(concertId, userId, ReservationStatus.SUCCESS);
        reservationRepository.save(reservation);
        log.info("[V6] DB 저장 완료 - reservationId={}, 차감 후 재고={}", reservation.getId(), concert.getStock());
    }

    private ReservationMessage deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, ReservationMessage.class);
        } catch (JacksonException e) {
            throw new RuntimeException("Kafka 메시지 역직렬화 실패: " + payload, e);
        }
    }
}
