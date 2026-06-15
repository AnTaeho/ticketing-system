package com.example.ticketing.waitingroom.service;

import com.example.ticketing.waitingroom.dto.QueueTokenResponse;
import com.example.ticketing.waitingroom.repository.QueueRedisRepository;
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

        if (queueRepository.admitOrEnqueue(concertId, token)) {
            log.info("[WaitingRoom-Queue] 처리열 즉시 입장 - userId={}, concertId={}", userId, concertId);
            return QueueTokenResponse.processing(token);
        } else {
            log.info("[WaitingRoom-Queue] 대기열 진입 - userId={}, concertId={}", userId, concertId);
            return QueueTokenResponse.waiting(token);
        }
    }

    public void removeFromProcessing(Long concertId, String token) {
        queueRepository.removeFromProcessing(concertId, token);
        log.info("[WaitingRoom-Queue] 처리열 토큰 제거 - concertId={}", concertId);
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
            int promoted = queueRepository.promoteWaiting(concertId);
            if (promoted > 0) {
                log.info("[WaitingRoom-Queue] 대기→처리 승격 - concertId={}, 승격수={}", concertId, promoted);
            }
        }
    }

    private Long extractConcertId(String key) {
        String[] parts = key.split(":");
        return Long.parseLong(parts[parts.length - 1]);
    }
}
