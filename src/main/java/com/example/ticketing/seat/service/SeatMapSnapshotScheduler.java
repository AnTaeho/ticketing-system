package com.example.ticketing.seat.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.metrics.Metrics.RebuildSource;
import com.example.ticketing.common.redis.InstanceId;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 회차마다 리스를 가진 인스턴스 하나만 좌석맵을 다시 만든다. 리스는 풀지 않고 매 주기 이어 가
// 서버 수와 무관하게 회차당 초당 한 번만 DB를 읽는다. 리더가 멈추면 리스 TTL 뒤 다른 서버가 잡는다
@Slf4j
@Component
@ConditionalOnProperty(name = "ticketing.seatmap.snapshot-enabled", havingValue = "true", matchIfMissing = true)
public class SeatMapSnapshotScheduler {

    private final SeatCatalog seatCatalog;
    private final SeatMapBuilder builder;
    private final SeatMapRedisStore store;
    private final String instanceId;
    private final Duration leaseTtl;
    private final Duration snapshotTtl;
    private final Counter rebuilds;
    private final ThrottledWarn failureWarn;

    public SeatMapSnapshotScheduler(SeatCatalog seatCatalog, SeatMapBuilder builder, SeatMapRedisStore store,
                                    InstanceId instanceId, LongSupplier nanoClock, TicketingProperties properties,
                                    MeterRegistry meterRegistry) {
        this.seatCatalog = seatCatalog;
        this.builder = builder;
        this.store = store;
        this.instanceId = instanceId.value();
        this.failureWarn = new ThrottledWarn(log, nanoClock);
        this.leaseTtl = properties.seatmap().leaseTtl();
        this.snapshotTtl = properties.seatmap().snapshotTtl();
        this.rebuilds = SeatMapBuilder.rebuildCounter(meterRegistry, RebuildSource.SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${ticketing.seatmap.snapshot-interval-ms}")
    public void run() {
        for (long performanceId : seatCatalog.openPerformanceIds()) {
            try {
                snapshotOne(performanceId);
            } catch (RedisUnavailableException e) {
                // 회차 하나의 실패가 나머지 회차의 스냅샷을 막지 않게 한다
                failureWarn.warn("[seatmap] 스냅샷 실패, 다음 회차로 넘어간다 performanceId={} cause={}", performanceId,
                        RedisFailures.describe(e));
            } catch (RuntimeException e) {
                log.warn("[seatmap] 스냅샷 실패, 다음 회차로 넘어간다 performanceId={}", performanceId, e);
            }
        }
    }

    // 반환: 이번 주기에 이 인스턴스가 스냅샷을 만들었는지
    boolean snapshotOne(long performanceId) {
        if (!store.tryLease(performanceId, instanceId, leaseTtl)) {
            return false;
        }
        Optional<String> json = builder.build(performanceId);
        rebuilds.increment();
        json.ifPresent(value -> store.put(performanceId, value, snapshotTtl));
        return json.isPresent();
    }
}
