package com.example.ticketing.seat.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.seat.domain.PerformanceInfo;
import com.example.ticketing.seat.domain.Seat;
import com.example.ticketing.seat.domain.SeatInfo;
import com.example.ticketing.seat.dto.SeatSelection;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.show.domain.PerformanceState;
import com.example.ticketing.show.dto.PerformanceSummary;
import com.example.ticketing.show.service.PerformanceReader;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

// 회차 시각과 좌석 위치·가격은 생성 뒤 바뀌지 않으므로 처음 조회 때 한 번 읽어 인스턴스에 둔다
@Component
public class SeatCatalog {

    private final SeatRepository seatRepository;
    private final PerformanceReader performanceReader;
    private final int maxSeats;

    private final ConcurrentMap<Long, PerformanceInfo> performances = new ConcurrentHashMap<>();

    public SeatCatalog(SeatRepository seatRepository, PerformanceReader performanceReader,
                       TicketingProperties properties) {
        this.seatRepository = seatRepository;
        this.performanceReader = performanceReader;
        this.maxSeats = properties.hold().maxSeats();
    }

    // 맵 잠금을 쥔 채 DB를 읽지 않으려고 computeIfAbsent를 쓰지 않는다. 겹쳐 읽어도 같은 값이다
    public PerformanceInfo performance(long performanceId) {
        PerformanceInfo cached = performances.get(performanceId);
        if (cached != null) {
            return cached;
        }
        PerformanceSummary summary = performanceReader.get(performanceId);
        Map<Long, SeatInfo> seats = new LinkedHashMap<>();
        for (Seat seat : seatRepository.findByPerformanceId(performanceId, Sort.by("id"))) {
            seats.put(seat.getId(), seat.info());
        }
        PerformanceInfo loaded = new PerformanceInfo(performanceId, summary.openAt(), summary.startsAt(),
                Collections.unmodifiableMap(seats));
        PerformanceInfo previous = performances.putIfAbsent(performanceId, loaded);
        return previous != null ? previous : loaded;
    }

    // 회차가 열려 있지 않으면 409 NOT_OPEN. 판정에는 DB 시각을, 사전 확인에는 서버 시각을 넘긴다
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

    // 지금 이 hold를 가리키는 좌석(HELD·SOLD)이다. 좌석 상태는 바뀌므로 매번 DB에서 읽는다
    public List<SeatInfo> seatsOfHold(long holdId) {
        return seatRepository.findByHoldIdOrderById(holdId).stream().map(Seat::info).toList();
    }

    private static ApiException invalidSelection(Map<String, Object> detail) {
        return new ApiException(ErrorCode.INVALID_SEAT_SELECTION, detail);
    }
}
