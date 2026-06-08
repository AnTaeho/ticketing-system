package com.example.ticketing.queue.service;

import com.example.ticketing.queue.controller.dto.QueueTokenResponse;
import com.example.ticketing.queue.repository.QueueRedisRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueCommandService {

    private final QueueRedisRepository queueRepository;

    public QueueTokenResponse issueTokenAndEnqueue(Long userId, Long concertId) {
        String token = UUID.randomUUID().toString();

        if (queueRepository.isProcessableNow(concertId)) {
            queueRepository.enqueueProcessing(concertId, token);
            log.info("[V7-Queue] 처리열 즉시 입장 - userId={}, concertId={}", userId, concertId);
            return QueueTokenResponse.processing(token);
        } else {
            queueRepository.enqueueWaiting(concertId, token);
            log.info("[V7-Queue] 대기열 진입 - userId={}, concertId={}", userId, concertId);
            return QueueTokenResponse.waiting(token);
        }
    }

    public void removeFromProcessing(Long concertId, String token) {
        queueRepository.removeFromProcessing(concertId, token);
        log.info("[V7-Queue] 처리열 토큰 제거 - concertId={}", concertId);
    }

    public void removeFromWaiting(Long concertId, String token) {
        queueRepository.removeFromWaiting(concertId, token);
    }

    public void purgeExpiredTokens() {
        queueRepository.purgeExpiredTokens();
    }

    public void promoteWaitingToProcessing() {
        Set<String> waitingKeys = queueRepository.findAllWaitingKeys();
        if (waitingKeys == null || waitingKeys.isEmpty()) return;

        for (String key : waitingKeys) {
            Long concertId = extractConcertId(key);
            int slots = queueRepository.getAvailableProcessingSlots(concertId);
            if (slots <= 0) continue;

            List<String> tokens = queueRepository.getFrontWaitingTokens(concertId, slots);
            tokens.forEach(token -> {
                queueRepository.promoteToProcessing(concertId, token);
                log.info("[V7-Queue] 대기→처리 승격 - concertId={}", concertId);
            });
        }
    }

    private Long extractConcertId(String key) {
        String[] parts = key.split(":");
        return Long.parseLong(parts[parts.length - 1]);
    }
}
