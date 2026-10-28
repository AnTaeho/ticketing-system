package com.example.ticketing.queue.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.ReflectOp;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.queue.domain.QueueEntry;
import com.example.ticketing.queue.domain.QueuePlacement;
import com.example.ticketing.queue.dto.QueueEnterResult;
import com.example.ticketing.queue.dto.QueueHeartbeatResult;
import com.example.ticketing.queue.dto.QueueStatusResult;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.seat.service.SeatMapQueryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 진입·조회는 Redis가 없으면 줄 순서를 지킬 수 없으므로 503으로 막는다(fail-closed)
// 점수는 Redis 안의 순서·만료 비교에만 쓰여 요청마다 DB 시각을 읽지 않고 서버 벽시계(ms)를 쓴다
@Slf4j
@Service
public class QueueService {

    private static final double MIN_RATE = 0.1;

    private final QueueRedisStore store;
    private final SeatCatalog seatCatalog;
    private final SeatMapQueryService seatMap;
    private final DbClock dbClock;
    private final Clock clock;
    private final int capacity;
    private final long idleTimeoutMs;
    private final long maxSessionMs;
    private final long leaveGraceMs;
    private final long waitingLeaveGraceMs;
    private final long waitingTtlMs;
    private final Counter consumeFailure;
    private final ThrottledWarn unavailableWarn;
    private final ThrottledWarn consumeWarn;

    public QueueService(QueueRedisStore store, SeatCatalog seatCatalog, SeatMapQueryService seatMap, DbClock dbClock,
                        Clock clock, LongSupplier nanoClock, TicketingProperties properties,
                        MeterRegistry meterRegistry) {
        this.store = store;
        this.seatCatalog = seatCatalog;
        this.seatMap = seatMap;
        this.dbClock = dbClock;
        this.clock = clock;
        this.unavailableWarn = new ThrottledWarn(log, nanoClock);
        this.consumeWarn = new ThrottledWarn(log, nanoClock);
        this.capacity = properties.queue().capacity();
        this.idleTimeoutMs = properties.queue().processingIdleTimeout().toMillis();
        this.maxSessionMs = properties.queue().processingMaxSession().toMillis();
        this.leaveGraceMs = properties.queue().processingLeaveGrace().toMillis();
        this.waitingLeaveGraceMs = properties.queue().waitingLeaveGrace().toMillis();
        this.waitingTtlMs = properties.queue().waitingTokenTtl().toMillis();
        this.consumeFailure = meterRegistry.counter(Metrics.REDIS_REFLECT_FAILURE, Tag.OP, ReflectOp.QUEUE_CONSUME);
    }

    // 상세 화면이 DB 시각으로 「예매하기」를 띄우므로 같은 기준으로 본다. 진입마다 DB를 치지 않게 추정 시각을 쓴다
    public QueueEnterResult enter(long userId, long performanceId) {
        seatCatalog.requireOpen(performanceId, dbClock.estimatedNow());
        String queueToken = userId + "." + UUID.randomUUID();
        long now = clock.millis();
        try {
            if (seatMap.isSoldOut(performanceId)) {
                throw new ApiException(ErrorCode.SOLD_OUT, Map.of("performanceId", performanceId));
            }
            QueuePlacement placed = store.enter(new QueueEntry(userId, performanceId, queueToken, now,
                    now + Math.min(idleTimeoutMs, maxSessionMs), capacity, waitingTtlMs));
            return new QueueEnterResult(placed.queueToken(), placed.status());
        } catch (RedisUnavailableException e) {
            throw unavailable("enter", performanceId, e);
        }
    }

    // 남의 토큰은 있는지 없는지 드러내지 않도록 없는 토큰과 같은 404로 답한다
    public QueueStatusResult status(long userId, long performanceId, String queueToken) {
        if (!ownedBy(queueToken, userId)) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        try {
            return lookUp(performanceId, queueToken, clock.millis());
        } catch (RedisUnavailableException e) {
            throw unavailable("status", performanceId, e);
        }
    }

    // 좌석 화면이 열려 있는 동안 처리열 자리를 붙잡는다. 입장 시각 + 최대 세션 길이를 넘겨 늘리지 않는다
    public QueueHeartbeatResult heartbeat(long userId, long performanceId, String queueToken) {
        if (!ownedBy(queueToken, userId)) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        long remainingMs;
        try {
            remainingMs = store.heartbeat(performanceId, queueToken, clock.millis(), idleTimeoutMs, maxSessionMs);
        } catch (RedisUnavailableException e) {
            throw unavailable("heartbeat", performanceId, e);
        }
        if (remainingMs <= 0) {
            throw new ApiException(ErrorCode.NOT_ADMITTED);
        }
        return QueueHeartbeatResult.processing(remainingMs);
    }

    // 화면을 떠날 때 처리열 자리는 잠깐만 더 남기고, 대기 순번은 유예 뒤 줄에서 뺀다. 소모·만료된 토큰에는 아무 일도 하지 않는다
    public void leave(long userId, long performanceId, String queueToken) {
        if (!ownedBy(queueToken, userId)) {
            throw new ApiException(ErrorCode.QUEUE_TOKEN_NOT_FOUND);
        }
        try {
            store.leave(performanceId, queueToken, clock.millis(), leaveGraceMs, waitingLeaveGraceMs, waitingTtlMs);
        } catch (RedisUnavailableException e) {
            throw unavailable("leave", performanceId, e);
        }
    }

    // 점수를 현재 시각과 비교하므로 정리 전의 만료 토큰도 통과하지 못한다. Redis 장애는 호출자가 처리한다
    public boolean isAdmitted(long userId, long performanceId, String queueToken) {
        if (!ownedBy(queueToken, userId)) {
            return false;
        }
        Double expireAt = store.processingScore(performanceId, queueToken);
        return expireAt != null && expireAt > clock.millis();
    }

    // 선점이 커밋된 뒤에만 부른다. 반환: 처리열에서 지웠는지
    public boolean consume(long userId, long performanceId, String queueToken) {
        if (!ownedBy(queueToken, userId)) {
            return false;
        }
        return store.consume(userId, performanceId, queueToken) > 0;
    }

    // 선점은 이미 커밋됐으므로 실패해도 응답을 바꾸지 않는다. 남은 토큰은 처리열 만료로 빠진다
    public void consumeQuietly(long userId, long performanceId, String queueToken) {
        try {
            consume(userId, performanceId, queueToken);
        } catch (RedisUnavailableException e) {
            consumeFailure.increment();
            consumeWarn.warn("[queue] Redis 반영 실패 op={} performanceId={} cause={}",
                    ReflectOp.QUEUE_CONSUME, performanceId, RedisFailures.describe(e));
        }
    }

    // 승격은 waiting → processing 한 방향이라 waiting을 먼저 봐야 그 사이 승격돼도 놓치지 않는다
    // 매진이면 이미 입장한 사람만 그대로 두고 나머지에게 매진을 알린다. 줄은 건드리지 않아 매진이 풀리면 이어서 기다린다
    private QueueStatusResult lookUp(long performanceId, String queueToken, long now) {
        if (seatMap.isSoldOut(performanceId)) {
            Double expireAt = store.processingScore(performanceId, queueToken);
            return expireAt != null && expireAt > now ? QueueStatusResult.processing() : QueueStatusResult.soldOut();
        }
        Double enteredAt = store.waitingScore(performanceId, queueToken);
        if (enteredAt != null && enteredAt + waitingTtlMs > now) {
            Long rank = store.waitingRank(performanceId, queueToken);
            if (rank != null) {
                // 순서를 묻는 화면이 있으면 떠난 게 아니다(새로고침)
                store.cancelLeave(performanceId, queueToken);
                long position = rank + 1;
                double rate = Math.max(store.rate(performanceId), MIN_RATE);
                return QueueStatusResult.waiting(position, (long) Math.ceil(position / rate));
            }
        }
        Double expireAt = store.processingScore(performanceId, queueToken);
        if (expireAt != null && expireAt > now) {
            return QueueStatusResult.processing();
        }
        return QueueStatusResult.expired();
    }

    private static boolean ownedBy(String queueToken, long userId) {
        if (queueToken == null) {
            return false;
        }
        int dot = queueToken.indexOf('.');
        return dot > 0 && queueToken.substring(0, dot).equals(String.valueOf(userId));
    }

    private ApiException unavailable(String op, long performanceId, RedisUnavailableException e) {
        unavailableWarn.warn("[queue] Redis 실패로 대기열을 막는다 op={} performanceId={} cause={}",
                op, performanceId, RedisFailures.describe(e));
        return new ApiException(ErrorCode.QUEUE_UNAVAILABLE);
    }
}
