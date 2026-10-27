package com.example.ticketing.booking.seat.map;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.SchedulerLease;
import com.example.ticketing.booking.seat.SeatCatalog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 회차마다 리스를 가진 인스턴스 하나만 좌석맵을 다시 만든다. 리스는 풀지 않고 매 주기 이어 가
// 서버 수와 무관하게 회차당 초당 한 번만 DB를 읽는다. 리더가 멈추면 리스 TTL 뒤 다른 서버가 잡는다
@Slf4j
@Component
@ConditionalOnProperty(name = "booking.seatmap.snapshot-enabled", havingValue = "true", matchIfMissing = true)
public class SeatMapSnapshotScheduler {

    private final SeatCatalog seatCatalog;
    private final SeatMapBuilder builder;
    private final SeatMapStore store;
    private final String instanceId;
    private final Duration leaseTtl;
    private final Duration snapshotTtl;
    private final Counter rebuilds;

    @Autowired
    public SeatMapSnapshotScheduler(SeatCatalog seatCatalog, SeatMapBuilder builder, SeatMapStore store,
                                    SchedulerLease schedulerLease, BookingProperties properties,
                                    MeterRegistry meterRegistry) {
        this(seatCatalog, builder, store, schedulerLease.instanceId(), properties, meterRegistry);
    }

    SeatMapSnapshotScheduler(SeatCatalog seatCatalog, SeatMapBuilder builder, SeatMapStore store,
                             String instanceId, BookingProperties properties, MeterRegistry meterRegistry) {
        this.seatCatalog = seatCatalog;
        this.builder = builder;
        this.store = store;
        this.instanceId = instanceId;
        this.leaseTtl = properties.seatmap().leaseTtl();
        this.snapshotTtl = properties.seatmap().snapshotTtl();
        this.rebuilds = SeatMapBuilder.rebuildCounter(meterRegistry, "scheduler");
    }

    @Scheduled(fixedDelayString = "${booking.seatmap.snapshot-interval-ms}")
    public void snapshot() {
        for (long performanceId : seatCatalog.openPerformanceIds()) {
            try {
                snapshotOne(performanceId);
            } catch (RuntimeException e) {
                log.warn("[booking seatmap] 회차 {} 스냅샷 실패, 다음 회차로 넘어간다: {}", performanceId, e.toString());
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
