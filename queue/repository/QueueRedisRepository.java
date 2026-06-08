package com.example.book_your_seat.queue.repository;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.example.book_your_seat.queue.QueueConst.*;

@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {

    private final StringRedisTemplate redisTemplate;

    @Resource(name = "redisTemplate")
    private ZSetOperations<String, String> zSet;

    public void enqueueProcessingQueue(Long concertId, String token) {
        zSet.add(generateKey(PROCESSING_QUEUE_KEY, concertId), token, System.currentTimeMillis() + PROCESSING_TOKEN_EXPIRATION_TIME);
    }

    public void enqueueWaitingQueue(Long concertId, String token) {
        zSet.add(generateKey(WAITING_QUEUE_KEY, concertId), token, System.currentTimeMillis() + WAITING_TOKEN_EXPIRATION_TIME);
    }

    /*
    바로 Processing 가능한지 여부 반환
     */
    public boolean isProcessableNow(Long concertId) {
        Long pqSize = zSet.zCard(generateKey(PROCESSING_QUEUE_KEY, concertId));
        Long wqSize = zSet.zCard(generateKey(WAITING_QUEUE_KEY, concertId));
        if (pqSize == null) pqSize = 0L;
        if (wqSize == null) wqSize = 0L;

        return pqSize < PROCESSING_QUEUE_SIZE && wqSize == 0;
    }

    public boolean isInProcessingQueue(Long concertId, String token) {
        Set<String> values = zSet.range(generateKey(PROCESSING_QUEUE_KEY, concertId), 0, -1);
        return values.stream().anyMatch(
                value -> value.equals(token)
        );
    }

    /*
    몇번째로 대기하고 있는지를 반환
     */
    public Integer getWaitingQueuePosition(Long concertId, String token) {
        Long rank = zSet.rank(generateKey(WAITING_QUEUE_KEY, concertId), token);
        return (rank == null) ? null : rank.intValue() + 1;
    }

    /*
    대기열에 존재 토큰 수 반환
     */
    public Integer getProcessingQueueCount(String concertId) {
        Long count = zSet.zCard(generateKey(PROCESSING_QUEUE_KEY, concertId));
        return (count == null) ? 0 : count.intValue();
    }


    /*
    업데이트 되어야 하는 토큰을 반환
     */
    public List<String> getFrontTokensFromWaitingQueue(String concertId, int count) {
        Set<String> tokens = zSet.range(generateKey(WAITING_QUEUE_KEY, concertId), 0, count - 1);
        return new ArrayList<>(tokens);
    }

    /*
    대기열에서 처리열로 이동
     */
    public void updateWaitingToProcessing(String concertId, String token) {
        zSet.remove(generateKey(WAITING_QUEUE_KEY, concertId), token);
        zSet.add(generateKey(PROCESSING_QUEUE_KEY, concertId), token, System.currentTimeMillis() + PROCESSING_TOKEN_EXPIRATION_TIME);
    }

    /*
     * 모든 Queue 키 가져오기
     */
    private Set<String> getAllQueueKeys() {
        Set<String> keys = redisTemplate.keys(PROCESSING_QUEUE_KEY + ":*");
        keys.addAll(redisTemplate.keys(WAITING_QUEUE_KEY + ":*"));
        keys.forEach(System.out::println);
        return keys;
    }

    /*
    유효시간 만료 토큰 삭제
     */
    public void removeExpiredToken(Long currentTime) {
        // 각 키에 대해 만료된 토큰 삭제
        for (String key : getAllQueueKeys()) {
            zSet.removeRangeByScore(key, Double.NEGATIVE_INFINITY, (double) currentTime);
        }
    }

    /*
    대기열을 보유하고 있는 콘서트키 반환
     */
    public Set<String> findConcertHavingWaiting() {
        return redisTemplate.keys(WAITING_QUEUE_KEY + ":*");
    }

    /*
    대기열에 있는 토큰 삭제
    */
    public void removeTokenInWaitingQueue(Long concertId, String token) {
        zSet.remove(generateKey(WAITING_QUEUE_KEY, concertId), token);
    }

    /*
    처리열에 있는 토큰 삭제 (API 완료 시, 예외 발생 시)
     */
    public void removeTokenInProcessingQueue(Long concertId, String token) {
        zSet.remove(generateKey(PROCESSING_QUEUE_KEY, concertId), token);
    }

    private <T> String generateKey(String keyName, T concertId) {
        return keyName + ":" + concertId.toString();
    }
}
