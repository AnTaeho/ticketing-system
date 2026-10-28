package com.example.ticketing.queue.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.common.redis.SchedulerLease;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.service.SeatCatalog;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 승격은 Lua가 정원을 지키므로 겹쳐 돌아도 넘치지 않지만, 한 서버만 돌도록 리스로 묶는다
@Slf4j
@Component
@ConditionalOnProperty(name = "ticketing.queue.promote-enabled", havingValue = "true", matchIfMissing = true)
public class QueuePromotionScheduler {

    static final String LEASE = "queue-promote";
    static final Duration RATE_TTL = Duration.ofSeconds(60);

    private final SchedulerLease lease;
    private final QueueRedisStore store;
    private final SeatCatalog seatCatalog;
    private final Clock clock;
    private final int capacity;
    private final long idleTimeoutMs;
    private final long maxSessionMs;
    private final long waitingTtlMs;
    private final double intervalSeconds;
    private final Duration leaseTtl;

    public QueuePromotionScheduler(SchedulerLease lease, QueueRedisStore store, SeatCatalog seatCatalog,
                                   Clock clock, TicketingProperties properties) {
        this.lease = lease;
        this.store = store;
        this.seatCatalog = seatCatalog;
        this.clock = clock;
        this.capacity = properties.queue().capacity();
        this.idleTimeoutMs = properties.queue().processingIdleTimeout().toMillis();
        this.maxSessionMs = properties.queue().processingMaxSession().toMillis();
        this.waitingTtlMs = properties.queue().waitingTokenTtl().toMillis();
        long intervalMs = properties.queue().promoteIntervalMs();
        this.intervalSeconds = intervalMs / 1000.0;
        this.leaseTtl = Duration.ofMillis(intervalMs * 2);
    }

    @Scheduled(fixedDelayString = "${ticketing.queue.promote-interval-ms}")
    public void run() {
        lease.runIfLeader(LEASE, leaseTtl, this::promoteAll);
    }

    void promoteAll() {
        for (long performanceId : seatCatalog.openPerformanceIds()) {
            try {
                promoteOne(performanceId);
            } catch (RedisUnavailableException e) {
                // 회차 하나의 실패가 나머지 회차의 승격을 막지 않게 한다. Redis가 통째로 죽으면 리스에서 주기가 멈춘다
                log.warn("[queue] 승격 실패, 다음 회차로 넘어간다 performanceId={} cause={}", performanceId,
                        RedisFailures.describe(e));
            } catch (RuntimeException e) {
                log.warn("[queue] 승격 실패, 다음 회차로 넘어간다 performanceId={}", performanceId, e);
            }
        }
    }

    // 만료를 먼저 치워야 빈 자리가 승격에 잡힌다. 점수가 진입 때 쓴 서버 벽시계(ms)라 같은 시계로 비교한다
    long promoteOne(long performanceId) {
        long now = clock.millis();
        store.purgeExpired(performanceId, now, waitingTtlMs, maxSessionMs);
        long moved = store.promote(performanceId, now, now + Math.min(idleTimeoutMs, maxSessionMs), capacity);
        double rate = 0.8 * store.rate(performanceId) + 0.2 * moved / intervalSeconds;
        store.saveRate(performanceId, rate, RATE_TTL);
        return moved;
    }
}
