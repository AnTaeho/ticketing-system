package com.example.ticketing.waitingroom.repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import static com.example.ticketing.waitingroom.WaitingRoomConst.*;

@Repository
@RequiredArgsConstructor
public class QueueRedisRepository {

    private static final long SCAN_COUNT = 100;

    /**
     * 입장 판정과 등록을 한 번에 처리(TOCTOU 제거).
     * 처리열에 빈자리가 있고 대기열이 비었으면 처리열에 등록(반환 1), 아니면 대기열에 등록(반환 0).
     * KEYS[1]=처리열, KEYS[2]=대기열 / ARGV[1]=처리만료score, ARGV[2]=대기만료score, ARGV[3]=정원, ARGV[4]=토큰
     */
    private static final DefaultRedisScript<Long> ADMIT_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[3]) and redis.call('ZCARD', KEYS[2]) == 0 then " +
            "  redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4]); return 1 " +
            "else " +
            "  redis.call('ZADD', KEYS[2], ARGV[2], ARGV[4]); return 0 " +
            "end",
            Long.class
    );

    /**
     * 처리열 빈자리만큼 대기열 선두 토큰을 원자적으로 승격. 이동한 토큰 수 반환.
     * KEYS[1]=처리열, KEYS[2]=대기열 / ARGV[1]=처리만료score, ARGV[2]=정원
     */
    private static final DefaultRedisScript<Long> PROMOTE_SCRIPT = new DefaultRedisScript<>(
            "local slots = tonumber(ARGV[2]) - redis.call('ZCARD', KEYS[1]); " +
            "if slots <= 0 then return 0 end; " +
            "local tokens = redis.call('ZRANGE', KEYS[2], 0, slots - 1); " +
            "for i = 1, #tokens do " +
            "  redis.call('ZREM', KEYS[2], tokens[i]); " +
            "  redis.call('ZADD', KEYS[1], ARGV[1], tokens[i]); " +
            "end; " +
            "return #tokens",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    /**
     * @return true=처리열 즉시 입장, false=대기열 진입
     */
    public boolean admitOrEnqueue(Long concertId, String token) {
        long now = System.currentTimeMillis();
        Long admitted = redisTemplate.execute(
                ADMIT_SCRIPT,
                List.of(processingKey(concertId), waitingKey(concertId)),
                String.valueOf(now + PROCESSING_TOKEN_TTL_MS),
                String.valueOf(now + WAITING_TOKEN_TTL_MS),
                String.valueOf(PROCESSING_QUEUE_SIZE),
                token
        );
        return Long.valueOf(1).equals(admitted);
    }

    /**
     * @return 대기→처리로 승격된 토큰 수
     */
    public int promoteWaiting(Long concertId) {
        long expiryScore = System.currentTimeMillis() + PROCESSING_TOKEN_TTL_MS;
        Long moved = redisTemplate.execute(
                PROMOTE_SCRIPT,
                List.of(processingKey(concertId), waitingKey(concertId)),
                String.valueOf(expiryScore),
                String.valueOf(PROCESSING_QUEUE_SIZE)
        );
        return moved == null ? 0 : moved.intValue();
    }

    public boolean isInProcessingQueue(Long concertId, String token) {
        return zSet().score(processingKey(concertId), token) != null;
    }

    public Integer getWaitingPosition(Long concertId, String token) {
        Long rank = zSet().rank(waitingKey(concertId), token);
        return rank == null ? null : rank.intValue() + 1;
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
        return scanKeys(WAITING_KEY_PREFIX + "*");
    }

    private void purgeKeys(String pattern, long now) {
        scanKeys(pattern).forEach(key ->
                zSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now));
    }

    /**
     * 프로덕션 Redis를 블로킹하는 {@code KEYS} 대신 커서 기반 {@code SCAN}으로 키를 순회한다.
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
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
