package com.example.ticketing.seat.service;

import com.example.ticketing.seat.domain.SeatMapSnapshot;
import com.example.ticketing.seat.domain.SeatMapSnapshot.Row;
import com.example.ticketing.seat.domain.SeatMapSnapshot.SeatCell;
import com.example.ticketing.seat.domain.SeatMapSnapshot.Section;
import com.example.ticketing.seat.domain.SeatStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 회차 좌석을 쿼리 한 번으로 읽어 스냅샷 JSON을 만든다. 재생성 카운터는 출처를 아는 호출자가 올린다
@Component
@RequiredArgsConstructor
public class SeatMapBuilder {

    private static final String SQL = """
            SELECT id, section, row_no, seat_no, grade, price, status
              FROM seat
             WHERE performance_id = ?
             ORDER BY section, row_no, seat_no""";

    static final String REBUILD_METRIC = "booking.seatmap.rebuild";

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    // 좌석이 없으면 빈 Optional. 빈 좌석맵은 저장하거나 내보내지 않는다
    public Optional<String> build(long performanceId) {
        List<Section> sections = new ArrayList<>();
        jdbcTemplate.query(SQL, rs -> {
            String section = rs.getString("section");
            int rowNo = rs.getInt("row_no");
            Section lastSection = sections.isEmpty() ? null : sections.get(sections.size() - 1);
            if (lastSection == null || !lastSection.section().equals(section)) {
                lastSection = new Section(section, new ArrayList<>());
                sections.add(lastSection);
            }
            List<Row> rows = lastSection.rows();
            Row lastRow = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (lastRow == null || lastRow.rowNo() != rowNo) {
                lastRow = new Row(rowNo, new ArrayList<>());
                rows.add(lastRow);
            }
            lastRow.seats().add(new SeatCell(
                    rs.getLong("id"),
                    rs.getInt("seat_no"),
                    rs.getString("grade"),
                    rs.getInt("price"),
                    SeatMapSnapshot.code(SeatStatus.valueOf(rs.getString("status")))));
        }, performanceId);
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        SeatMapSnapshot snapshot = new SeatMapSnapshot(performanceId, System.currentTimeMillis(), sections);
        return Optional.of(jsonMapper.writeValueAsString(snapshot));
    }

    // source: scheduler | miss | fallback
    static Counter rebuildCounter(MeterRegistry meterRegistry, String source) {
        return meterRegistry.counter(REBUILD_METRIC, "source", source);
    }
}
