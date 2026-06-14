package com.example.ticketing.reservation.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5;
    private static final long STUCK_THRESHOLD_MINUTES = 5;
    private static final long RECOVERY_INTERVAL_MS = 300_000;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${ticketing.kafka.topic}")
    private String topic;

    // 정상 경로: 트랜잭션 커밋 직후 즉시 발행
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxCreated(OutboxCreatedEvent event) {
        outboxEventRepository.findById(event.outboxEventId())
                .ifPresent(this::publishAndSave);
    }

    // 복구 경로: 크래시/Kafka 장애로 발행 못 한 이벤트 재처리 (5분마다)
    @Scheduled(fixedDelay = RECOVERY_INTERVAL_MS)
    public void recoverStuckEvents() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);
        List<OutboxEvent> stuck = outboxEventRepository
                .findByStatusAndCreatedAtBefore(OutboxStatus.PENDING, threshold);

        if (!stuck.isEmpty()) {
            log.warn("[Outbox] 미발행 이벤트 복구 시작 - count={}", stuck.size());
            stuck.forEach(this::publishAndSave);
        }

        alertDeadLetteredEvents();
    }

    // MAX_RETRY 초과로 FAILED 격리된 이벤트는 복구 대상에서 제외되므로, 방치되지 않도록 노출한다.
    private void alertDeadLetteredEvents() {
        long failedCount = outboxEventRepository.countByStatus(OutboxStatus.FAILED);
        if (failedCount > 0) {
            log.error("[Outbox] 재시도 한도 초과로 격리된 이벤트 존재 - count={}, 수동 조치 필요", failedCount);
        }
    }

    private void publishAndSave(OutboxEvent event) {
        try {
            kafkaTemplate.send(topic, String.valueOf(event.getConcertId()), event.getPayload())
                    .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.markPublished();
            log.info("[Outbox] 발행 성공 - outboxId={}, ticketToken={}", event.getId(), event.getTicketToken());
        } catch (Exception e) {
            event.incrementRetry();
            log.error("[Outbox] 발행 실패 - outboxId={}, retryCount={}", event.getId(), event.getRetryCount(), e);
        }
        outboxEventRepository.save(event);
    }
}
