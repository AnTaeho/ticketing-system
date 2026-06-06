package com.example.ticketing.reservation.kafka;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
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

    public void publishReservationRequest(Long concertId, Long userId) {
        ReservationMessage message = new ReservationMessage(concertId, userId);

        try {
            String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(topic, String.valueOf(concertId), payload);
            log.info("[V6] DB 처리 요청 발행 - concertId={}, userId={}", concertId, userId);
        } catch (JacksonException e) {
            throw new RuntimeException("Kafka 메시지 직렬화 실패", e);
        }
    }
}
