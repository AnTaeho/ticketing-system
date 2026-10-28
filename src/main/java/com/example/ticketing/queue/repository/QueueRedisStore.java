package com.example.ticketing.queue.repository;

import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.RedisScripts;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.queue.domain.QueueEntry;
import com.example.ticketing.queue.domain.QueuePlacement;
import com.example.ticketing.queue.domain.QueueTokenStatus;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// waiting 점수는 진입 시각이다. 만료는 진입 시각 + waiting 수명으로 판정해 FIFO 순서와 만료를 한 점수로 다룬다
// Redis 장애는 RedisUnavailableException으로 던진다
@Component
public class QueueRedisStore {

    private final StringRedisTemplate redis;

    public QueueRedisStore(TicketingRedis ticketingRedis) {
        this.redis = ticketingRedis.template();
    }

    public QueuePlacement enter(QueueEntry entry) {
        long pid = entry.performanceId();
        List<?> reply = RedisFailures.translate(() -> redis.execute(RedisScripts.QUEUE_ENTER,
                List.of(RedisKeys.queueProcessing(pid), RedisKeys.queueWaiting(pid),
                        RedisKeys.queueUser(entry.userId(), pid), RedisKeys.queueAdmitted(pid)),
                entry.queueToken(), String.valueOf(entry.enteredAtMs()), String.valueOf(entry.processingExpireAtMs()),
                String.valueOf(entry.enteredAtMs()), String.valueOf(entry.capacity()),
                String.valueOf(entry.waitingTtlMs())));
        if (reply == null || reply.size() != 2) {
            throw new IllegalStateException("queue_enter.lua 응답이 예상과 다르다: " + reply);
        }
        return new QueuePlacement(QueueTokenStatus.valueOf(String.valueOf(reply.get(0))),
                String.valueOf(reply.get(1)));
    }

    // 반환: 승격한 수. 승격 토큰의 입장 시각은 nowMs다
    public long promote(long performanceId, long nowMs, long processingExpireAtMs, int capacity) {
        Long moved = RedisFailures.translate(() -> redis.execute(RedisScripts.QUEUE_PROMOTE,
                List.of(RedisKeys.queueProcessing(performanceId), RedisKeys.queueWaiting(performanceId),
                        RedisKeys.queueAdmitted(performanceId)),
                String.valueOf(processingExpireAtMs), String.valueOf(capacity), String.valueOf(nowMs)));
        return moved == null ? 0 : moved;
    }

    // 0부터 센다. 없으면 null
    public Long waitingRank(long performanceId, String queueToken) {
        return RedisFailures.translate(
                () -> redis.opsForZSet().rank(RedisKeys.queueWaiting(performanceId), queueToken));
    }

    public Double waitingScore(long performanceId, String queueToken) {
        return RedisFailures.translate(
                () -> redis.opsForZSet().score(RedisKeys.queueWaiting(performanceId), queueToken));
    }

    public Double processingScore(long performanceId, String queueToken) {
        return RedisFailures.translate(
                () -> redis.opsForZSet().score(RedisKeys.queueProcessing(performanceId), queueToken));
    }

    // 반환: processing에서 지운 수
    public long consume(long userId, long performanceId, String queueToken) {
        Long removed = RedisFailures.translate(() -> redis.execute(RedisScripts.QUEUE_CONSUME,
                List.of(RedisKeys.queueProcessing(performanceId), RedisKeys.queueUser(userId, performanceId),
                        RedisKeys.queueAdmitted(performanceId)),
                queueToken));
        return removed == null ? 0 : removed;
    }

    // 반환: 늘린 뒤 남은 시간(ms). 만료됐거나 처리열에 없으면 0
    public long heartbeat(long performanceId, String queueToken, long nowMs, long idleTimeoutMs, long maxSessionMs) {
        Long remaining = RedisFailures.translate(() -> redis.execute(RedisScripts.QUEUE_HEARTBEAT,
                List.of(RedisKeys.queueProcessing(performanceId), RedisKeys.queueAdmitted(performanceId)),
                queueToken, String.valueOf(nowMs), String.valueOf(idleTimeoutMs), String.valueOf(maxSessionMs)));
        return remaining == null ? 0 : remaining;
    }

    // 반환: 점수를 줄였는지. 처리열에 없거나 만료됐거나 이미 짧으면 false
    public boolean leave(long performanceId, String queueToken, long nowMs, long graceMs) {
        Long shortened = RedisFailures.translate(() -> redis.execute(RedisScripts.QUEUE_LEAVE,
                List.of(RedisKeys.queueProcessing(performanceId)),
                queueToken, String.valueOf(nowMs), String.valueOf(graceMs)));
        return shortened != null && shortened > 0;
    }

    // 반환: 두 열에서 지운 수의 합. 처리열에서 빠진 토큰은 입장 시각도 함께 지운다
    public long purgeExpired(long performanceId, long nowMs, long waitingTtlMs, long maxSessionMs) {
        Long removed = RedisFailures.translate(() -> redis.execute(RedisScripts.QUEUE_PURGE,
                List.of(RedisKeys.queueProcessing(performanceId), RedisKeys.queueWaiting(performanceId),
                        RedisKeys.queueAdmitted(performanceId)),
                String.valueOf(nowMs), String.valueOf(waitingTtlMs), String.valueOf(maxSessionMs)));
        return removed == null ? 0 : removed;
    }

    // 명/초. 기록이 없으면 0
    public double rate(long performanceId) {
        String raw = RedisFailures.translate(() -> redis.opsForValue().get(RedisKeys.queueRate(performanceId)));
        if (raw == null) {
            return 0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void saveRate(long performanceId, double rate, Duration ttl) {
        RedisFailures.translate(
                () -> redis.opsForValue().set(RedisKeys.queueRate(performanceId), String.valueOf(rate), ttl));
    }
}
