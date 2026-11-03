package com.example.ticketing.seat.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.seat.domain.PerformanceInfo;
import com.example.ticketing.seat.domain.SeatInfo;
import com.example.ticketing.seat.dto.SeatSelection;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.show.domain.PerformanceState;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

// 회차 시각과 좌석 위치·가격은 생성 뒤 바뀌지 않으므로 처음 조회 때 한 번 읽어 인스턴스에 둔다
@Component
public class SeatCatalog {

    private static final long OPEN_IDS_TTL_NANOS = Duration.ofSeconds(10).toNanos();

    private final SeatRepository seatRepository;
    private final int maxSeats;
    private final LongSupplier nanoClock;

    private final ConcurrentMap<Long, PerformanceInfo> performances = new ConcurrentHashMap<>();
    private final Object openIdsLock = new Object();
    private volatile OpenIds openIds;

    public SeatCatalog(SeatRepository seatRepository, TicketingProperties properties,
                       LongSupplier nanoClock) {
        this.seatRepository = seatRepository;
        this.maxSeats = properties.hold().maxSeats();
        this.nanoClock = nanoClock;
    }

    public PerformanceInfo performance(long performanceId) {
        PerformanceInfo cached = performances.get(performanceId);
        if (cached != null) {
            return cached;
        }
        PerformanceInfo loaded = seatRepository.findPerformanceInfo(performanceId)
                .map(info -> info.withSeats(seatRepository.findSeatInfos(performanceId)))
                .orElseThrow(() -> new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND,
                        Map.of("performanceId", performanceId)));
        PerformanceInfo previous = performances.putIfAbsent(performanceId, loaded);
        return previous != null ? previous : loaded;
    }

    // 회차가 열려 있지 않으면 409 NOT_OPEN. now는 DB 시각이나 DB 시각 추정값을 넘긴다
    public void requireOpen(long performanceId, LocalDateTime now) {
        if (performance(performanceId).stateAt(now) != PerformanceState.OPEN) {
            throw new ApiException(ErrorCode.NOT_OPEN, Map.of("performanceId", performanceId));
        }
    }

    public SeatSelection validateSeats(long performanceId, Collection<Long> seatIds) {
        PerformanceInfo performance = performance(performanceId);
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > maxSeats) {
            throw invalidSelection(Map.of("reason", "COUNT", "maxSeats", maxSeats));
        }
        if (seatIds.stream().anyMatch(Objects::isNull)) {
            throw invalidSelection(Map.of("reason", "UNKNOWN_SEAT"));
        }
        TreeSet<Long> sorted = new TreeSet<>(seatIds);
        if (sorted.size() != seatIds.size()) {
            throw invalidSelection(Map.of("reason", "DUPLICATE"));
        }
        List<Long> unknown = sorted.stream().filter(id -> !performance.seats().containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw invalidSelection(Map.of("reason", "UNKNOWN_SEAT", "seatIds", unknown));
        }
        List<SeatInfo> seats = sorted.stream().map(performance.seats()::get).toList();
        int totalAmount = seats.stream().mapToInt(SeatInfo::price).sum();
        return new SeatSelection(performanceId, List.copyOf(sorted), seats, totalAmount);
    }

    // 지금 이 hold를 가리키는 좌석. 풀리거나 남이 가져간 좌석은 빠지고, 위치·가격은 카탈로그에서 채운다
    public List<SeatInfo> seatsOfHold(long performanceId, long holdId) {
        Map<Long, SeatInfo> seats = performance(performanceId).seats();
        return seatRepository.findSeatIdsOfHold(performanceId, holdId).stream().map(seats::get).toList();
    }

    // 스케줄러가 매 주기 부르므로 10초 묶어 둔다
    public List<Long> openPerformanceIds() {
        OpenIds current = openIds;
        if (current != null && current.isFresh(nanoClock.getAsLong())) {
            return current.ids();
        }
        synchronized (openIdsLock) {
            current = openIds;
            if (current != null && current.isFresh(nanoClock.getAsLong())) {
                return current.ids();
            }
            List<Long> ids = seatRepository.findOpenPerformanceIds();
            openIds = new OpenIds(List.copyOf(ids), nanoClock.getAsLong());
            return openIds.ids();
        }
    }

    private static ApiException invalidSelection(Map<String, Object> detail) {
        return new ApiException(ErrorCode.INVALID_SEAT_SELECTION, detail);
    }

    private record OpenIds(List<Long> ids, long loadedAtNanos) {

        boolean isFresh(long nowNanos) {
            return nowNanos - loadedAtNanos < OPEN_IDS_TTL_NANOS;
        }
    }
}
