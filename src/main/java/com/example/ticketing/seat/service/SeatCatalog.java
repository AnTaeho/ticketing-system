package com.example.ticketing.seat.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.seat.domain.SeatGrade;
import java.time.Duration;
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
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 회차 시각과 좌석 위치·가격은 생성 뒤 바뀌지 않으므로 처음 조회 때 한 번 읽어 인스턴스에 둔다
@Component
@RequiredArgsConstructor
public class SeatCatalog {

    private static final long OPEN_IDS_TTL_NANOS = Duration.ofSeconds(10).toNanos();

    private final JdbcTemplate jdbcTemplate;
    private final TicketingProperties properties;
    private final LongSupplier nanoClock;

    private final ConcurrentMap<Long, PerformanceInfo> performances = new ConcurrentHashMap<>();
    private final Object openIdsLock = new Object();
    private volatile OpenIds openIds;

    public PerformanceInfo performance(long performanceId) {
        PerformanceInfo cached = performances.get(performanceId);
        if (cached != null) {
            return cached;
        }
        PerformanceInfo loaded = load(performanceId);
        PerformanceInfo previous = performances.putIfAbsent(performanceId, loaded);
        return previous != null ? previous : loaded;
    }

    public SeatSelection validateSeats(long performanceId, Collection<Long> seatIds) {
        PerformanceInfo performance = performance(performanceId);
        int maxSeats = properties.hold().maxSeats();
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

    // 예매 중인 회차: open_at <= NOW(6) < starts_at. 스케줄러가 매 주기 부르므로 10초 묶어 둔다
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
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM performance WHERE open_at <= NOW(6) AND starts_at > NOW(6) ORDER BY id",
                    Long.class);
            openIds = new OpenIds(List.copyOf(ids), nanoClock.getAsLong());
            return openIds.ids();
        }
    }

    private PerformanceInfo load(long performanceId) {
        List<PerformanceInfo> found = jdbcTemplate.query(
                "SELECT show_id, open_at, starts_at, gate_close_at FROM performance WHERE id = ?",
                (rs, i) -> new PerformanceInfo(performanceId, rs.getLong("show_id"),
                        rs.getObject("open_at", LocalDateTime.class),
                        rs.getObject("starts_at", LocalDateTime.class),
                        rs.getObject("gate_close_at", LocalDateTime.class),
                        Map.of()),
                performanceId);
        if (found.isEmpty()) {
            throw new ApiException(ErrorCode.PERFORMANCE_NOT_FOUND, Map.of("performanceId", performanceId));
        }
        Map<Long, SeatInfo> seats = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT id, section, row_no, seat_no, grade, price FROM seat WHERE performance_id = ? ORDER BY id",
                rs -> {
                    long id = rs.getLong("id");
                    seats.put(id, new SeatInfo(id, rs.getString("section"), rs.getInt("row_no"),
                            rs.getInt("seat_no"), SeatGrade.valueOf(rs.getString("grade")), rs.getInt("price")));
                },
                performanceId);
        PerformanceInfo times = found.get(0);
        return new PerformanceInfo(performanceId, times.showId(), times.openAt(), times.startsAt(),
                times.gateCloseAt(), Collections.unmodifiableMap(seats));
    }

    private static ApiException invalidSelection(Map<String, Object> detail) {
        return new ApiException(ErrorCode.INVALID_SEAT_SELECTION, detail);
    }

    public record SeatInfo(long id, String section, int rowNo, int seatNo, SeatGrade grade, int price) {
    }

    public record PerformanceInfo(
            long performanceId,
            long showId,
            LocalDateTime openAt,
            LocalDateTime startsAt,
            LocalDateTime gateCloseAt,
            Map<Long, SeatInfo> seats
    ) {
    }

    // seatIds·seats는 좌석 ID 오름차순이다
    public record SeatSelection(long performanceId, List<Long> seatIds, List<SeatInfo> seats, int totalAmount) {
    }

    private record OpenIds(List<Long> ids, long loadedAtNanos) {

        boolean isFresh(long nowNanos) {
            return nowNanos - loadedAtNanos < OPEN_IDS_TTL_NANOS;
        }
    }
}
