package com.example.ticketing.seat.repository;

import com.example.ticketing.seat.domain.PerformanceInfo;
import com.example.ticketing.seat.domain.SeatGrade;
import com.example.ticketing.seat.domain.SeatInfo;
import com.example.ticketing.seat.domain.SeatMapRow;
import com.example.ticketing.seat.domain.SeatStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatJdbcRepository {

    private static final String PERFORMANCE_SQL =
            "SELECT show_id, open_at, starts_at, gate_close_at FROM performance WHERE id = :pid";

    private static final String SEAT_INFO_SQL =
            "SELECT id, section, row_no, seat_no, grade, price FROM seat WHERE performance_id = :pid ORDER BY id";

    // 예매 중인 회차: open_at <= NOW(6) < starts_at
    private static final String OPEN_PERFORMANCE_IDS_SQL =
            "SELECT id FROM performance WHERE open_at <= NOW(6) AND starts_at > NOW(6) ORDER BY id";

    private static final String SEAT_MAP_SQL = """
            SELECT id, section, row_no, seat_no, grade, price, status
              FROM seat
             WHERE performance_id = :pid
             ORDER BY section, row_no, seat_no""";

    private static final String HELD_SEATS_OF_HOLDS_SQL = """
            SELECT id, hold_id FROM seat
             WHERE hold_id IN (:holdIds) AND status = 'HELD'
             ORDER BY id""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 회차 행과 좌석을 함께 읽는다. 회차가 없으면 빈 Optional
    public Optional<PerformanceInfo> findPerformanceWithSeats(long performanceId) {
        Map<String, Long> pid = Map.of("pid", performanceId);
        List<PerformanceInfo> found = jdbcTemplate.query(PERFORMANCE_SQL, pid,
                (rs, i) -> new PerformanceInfo(performanceId, rs.getLong("show_id"),
                        rs.getObject("open_at", LocalDateTime.class),
                        rs.getObject("starts_at", LocalDateTime.class),
                        rs.getObject("gate_close_at", LocalDateTime.class),
                        Map.of()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, SeatInfo> seats = new LinkedHashMap<>();
        jdbcTemplate.query(SEAT_INFO_SQL, pid, rs -> {
            long id = rs.getLong("id");
            seats.put(id, new SeatInfo(id, rs.getString("section"), rs.getInt("row_no"), rs.getInt("seat_no"),
                    SeatGrade.valueOf(rs.getString("grade")), rs.getInt("price")));
        });
        PerformanceInfo times = found.get(0);
        return Optional.of(new PerformanceInfo(performanceId, times.showId(), times.openAt(), times.startsAt(),
                times.gateCloseAt(), Collections.unmodifiableMap(seats)));
    }

    public List<Long> findOpenPerformanceIds() {
        return jdbcTemplate.queryForList(OPEN_PERFORMANCE_IDS_SQL, Map.of(), Long.class);
    }

    // 구역 → 열 → 번호 순이다
    public List<SeatMapRow> findSeatMapRows(long performanceId) {
        return jdbcTemplate.query(SEAT_MAP_SQL, Map.of("pid", performanceId),
                (rs, i) -> new SeatMapRow(rs.getLong("id"), rs.getString("section"), rs.getInt("row_no"),
                        rs.getInt("seat_no"), rs.getString("grade"), rs.getInt("price"),
                        SeatStatus.valueOf(rs.getString("status"))));
    }

    // hold마다 아직 HELD로 쥐고 있는 좌석 ID(오름차순). 좌석이 없는 hold는 결과에 없다
    public Map<Long, List<Long>> findHeldSeatIdsByHold(Collection<Long> holdIds) {
        Map<Long, List<Long>> seatsByHold = new HashMap<>();
        jdbcTemplate.query(HELD_SEATS_OF_HOLDS_SQL, Map.of("holdIds", holdIds), rs -> {
            seatsByHold.computeIfAbsent(rs.getLong("hold_id"), k -> new ArrayList<>()).add(rs.getLong("id"));
        });
        return seatsByHold;
    }
}
