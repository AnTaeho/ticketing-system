package com.example.ticketing.queue.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import static com.example.ticketing.queue.QueueConst.*;

@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {

    private final StringRedisTemplate redisTemplate;

    public void enqueueProcessing(Long concertId, String token) {
        double expiryScore = System.currentTimeMillis() + PROCESSING_TOKEN_TTL_MS;
        zSet().add(processingKey(concertId), token, expiryScore);
    }

    public void enqueueWaiting(Long concertId, String token) {
        double expiryScore = System.currentTimeMillis() + WAITING_TOKEN_TTL_MS;
        zSet().add(waitingKey(concertId), token, expiryScore);
    }

    public boolean isProcessableNow(Long concertId) {
        long processingSize = size(processingKey(concertId));
        long waitingSize = size(waitingKey(concertId));
        return processingSize < PROCESSING_QUEUE_SIZE && waitingSize == 0;
    }

    public boolean isInProcessingQueue(Long concertId, String token) {
        return zSet().score(processingKey(concertId), token) != null;
    }

    public Integer getWaitingPosition(Long concertId, String token) {
        Long rank = zSet().rank(waitingKey(concertId), token);
        return rank == null ? null : rank.intValue() + 1;
    }

    public int getAvailableProcessingSlots(Long concertId) {
        long current = size(processingKey(concertId));
        return (int) Math.max(0, PROCESSING_QUEUE_SIZE - current);
    }

    public List<String> getFrontWaitingTokens(Long concertId, int count) {
        Set<String> tokens = zSet().range(waitingKey(concertId), 0, count - 1);
        return tokens == null ? List.of() : new ArrayList<>(tokens);
    }

    public void promoteToProcessing(Long concertId, String token) {
        zSet().remove(waitingKey(concertId), token);
        double expiryScore = System.currentTimeMillis() + PROCESSING_TOKEN_TTL_MS;
        zSet().add(processingKey(concertId), token, expiryScore);
    }

    public void removeFromProcessing(Long concertId, String token) {
        zSet().remove(processingKey(concertId), token);
    }

    public void removeFromWaiting(Long concertId, String token) {
        zSet().remove(waitingKey(concertId), token);
    }

    public void purgeExpiredTokens() {
        long now = System.currentTimeMillis();
        purgeKeys(PROCESSING_KEY_PREFIX + "*", now);
        purgeKeys(WAITING_KEY_PREFIX + "*", now);
    }

    public int getProcessingCount(Long concertId) {
        Long count = zSet().zCard(processingKey(concertId));
        return count == null ? 0 : count.intValue();
    }

    public int getWaitingCount(Long concertId) {
        Long count = zSet().zCard(waitingKey(concertId));
        return count == null ? 0 : count.intValue();
    }

    public void clearQueues(Long concertId) {
        redisTemplate.delete(processingKey(concertId));
        redisTemplate.delete(waitingKey(concertId));
    }

    public Set<String> findAllWaitingKeys() {
        return redisTemplate.keys(WAITING_KEY_PREFIX + "*");
    }

    private void purgeKeys(String pattern, long now) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null) {
            keys.forEach(key -> zSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now));
        }
    }

    private long size(String key) {
        Long count = zSet().zCard(key);
        return count == null ? 0L : count;
    }

    private ZSetOperations<String, String> zSet() {
        return redisTemplate.opsForZSet();
    }

    private String processingKey(Long concertId) {
        return PROCESSING_KEY_PREFIX + concertId;
    }

    private String waitingKey(Long concertId) {
        return WAITING_KEY_PREFIX + concertId;
    }
}
