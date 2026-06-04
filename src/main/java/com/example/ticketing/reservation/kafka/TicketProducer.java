package com.example.ticketing.reservation.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ticketing.kafka.topic}")
    private String topic;

    public String publishReservationRequest(Long concertId, Long userId) {
        String ticketToken = UUID.randomUUID().toString();
        ReservationMessage message = new ReservationMessage(ticketToken, concertId, userId);

        try {
            String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(topic, String.valueOf(concertId), payload);
            log.info("[V6] 예약 요청 발행 - ticketToken={}, concertId={}, userId={}", ticketToken, concertId, userId);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Kafka 메시지 직렬화 실패", e);
        }

        return ticketToken;
    }
}
