package com.example.ticketing.queue.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.redis.SchedulerLease;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.seat.service.SeatCatalog;
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
    private final QueueRedisStore repository;
    private final SeatCatalog seatCatalog;
    private final int capacity;
    private final long processingTtlMs;
    private final long waitingTtlMs;
    private final double intervalSeconds;
    private final Duration leaseTtl;

    public QueuePromotionScheduler(SchedulerLease lease, QueueRedisStore repository, SeatCatalog seatCatalog,
                                   TicketingProperties properties) {
        this.lease = lease;
        this.repository = repository;
        this.seatCatalog = seatCatalog;
        this.capacity = properties.queue().capacity();
        this.processingTtlMs = properties.queue().processingTokenTtl().toMillis();
        this.waitingTtlMs = properties.queue().waitingTokenTtl().toMillis();
        long intervalMs = properties.queue().promoteIntervalMs();
        this.intervalSeconds = intervalMs / 1000.0;
        this.leaseTtl = Duration.ofMillis(intervalMs * 2);
    }

    @Scheduled(fixedDelayString = "${ticketing.queue.promote-interval-ms}")
    public void promote() {
        lease.runIfLeader(LEASE, leaseTtl, this::promoteAll);
    }

    void promoteAll() {
        for (long performanceId : seatCatalog.openPerformanceIds()) {
            try {
                promoteOne(performanceId);
            } catch (RuntimeException e) {
                log.warn("[booking queue] 회차 {} 승격 실패, 다음 회차로 넘어간다: {}", performanceId, e.toString());
            }
        }
    }

    // 만료를 먼저 치워야 빈 자리가 승격에 잡힌다. 반환: 승격한 수
    long promoteOne(long performanceId) {
        long now = System.currentTimeMillis();
        repository.purgeExpired(performanceId, now, waitingTtlMs);
        long moved = repository.promote(performanceId, now + processingTtlMs, capacity);
        double rate = 0.8 * repository.rate(performanceId) + 0.2 * moved / intervalSeconds;
        repository.saveRate(performanceId, rate, RATE_TTL);
        return moved;
    }
}
