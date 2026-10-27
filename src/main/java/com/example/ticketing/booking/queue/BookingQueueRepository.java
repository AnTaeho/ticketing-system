package com.example.ticketing.booking.queue;

import com.example.ticketing.booking.common.BookingRedis;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// waiting 점수는 진입 시각이다. 만료는 진입 시각 + waiting 수명으로 판정해 FIFO 순서와 만료를 한 점수로 다룬다
@Component
public class BookingQueueRepository {

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ENTER = script("queue_enter.lua", List.class);
    private static final DefaultRedisScript<Long> PROMOTE = script("queue_promote.lua", Long.class);
    private static final DefaultRedisScript<Long> CONSUME = script("queue_consume.lua", Long.class);

    private final StringRedisTemplate redis;

    public BookingQueueRepository(BookingRedis bookingRedis) {
        this.redis = bookingRedis.template();
    }

    // 이미 살아 있는 토큰이 있으면 그 토큰과 상태를 돌려준다
    public Entered enter(long performanceId, long userId, String member, long nowMs,
                         long processingExpireAtMs, int capacity, long waitingTtlMs) {
        List<?> reply = redis.execute(ENTER,
                List.of(QueueKeys.processing(performanceId), QueueKeys.waiting(performanceId),
                        QueueKeys.user(performanceId, userId)),
                member, String.valueOf(nowMs), String.valueOf(processingExpireAtMs), String.valueOf(nowMs),
                String.valueOf(capacity), String.valueOf(waitingTtlMs));
        if (reply == null || reply.size() != 2) {
            throw new IllegalStateException("queue_enter.lua 응답이 예상과 다르다: " + reply);
        }
        return new Entered(QueueTokenStatus.valueOf(String.valueOf(reply.get(0))), String.valueOf(reply.get(1)));
    }

    // 반환: 승격한 수
    public long promote(long performanceId, long processingExpireAtMs, int capacity) {
        Long moved = redis.execute(PROMOTE,
                List.of(QueueKeys.processing(performanceId), QueueKeys.waiting(performanceId)),
                String.valueOf(processingExpireAtMs), String.valueOf(capacity));
        return moved == null ? 0 : moved;
    }

    // 0부터 센다. 없으면 null
    public Long waitingRank(long performanceId, String member) {
        return redis.opsForZSet().rank(QueueKeys.waiting(performanceId), member);
    }

    public Double waitingScore(long performanceId, String member) {
        return redis.opsForZSet().score(QueueKeys.waiting(performanceId), member);
    }

    public Double processingScore(long performanceId, String member) {
        return redis.opsForZSet().score(QueueKeys.processing(performanceId), member);
    }

    // 반환: processing에서 지운 수
    public long consume(long performanceId, long userId, String member) {
        Long removed = redis.execute(CONSUME,
                List.of(QueueKeys.processing(performanceId), QueueKeys.user(performanceId, userId)), member);
        return removed == null ? 0 : removed;
    }

    // 반환: 두 열에서 지운 수의 합
    public long purgeExpired(long performanceId, long nowMs, long waitingTtlMs) {
        Long processing = redis.opsForZSet().removeRangeByScore(
                QueueKeys.processing(performanceId), Double.NEGATIVE_INFINITY, nowMs);
        Long waiting = redis.opsForZSet().removeRangeByScore(
                QueueKeys.waiting(performanceId), Double.NEGATIVE_INFINITY, nowMs - waitingTtlMs);
        return (processing == null ? 0 : processing) + (waiting == null ? 0 : waiting);
    }

    // 명/초. 기록이 없으면 0
    public double rate(long performanceId) {
        String raw = redis.opsForValue().get(QueueKeys.rate(performanceId));
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
        redis.opsForValue().set(QueueKeys.rate(performanceId), String.valueOf(rate), ttl);
    }

    public record Entered(QueueTokenStatus status, String member) {
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/booking/" + name));
        script.setResultType(resultType);
        return script;
    }
}
