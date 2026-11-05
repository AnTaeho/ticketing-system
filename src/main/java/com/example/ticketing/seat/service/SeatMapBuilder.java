package com.example.ticketing.seat.service;

import com.example.ticketing.seat.domain.SeatMapSeat;
import com.example.ticketing.seat.domain.SeatMapSnapshot;
import com.example.ticketing.seat.domain.SeatMapSnapshot.Row;
import com.example.ticketing.seat.domain.SeatMapSnapshot.SeatCell;
import com.example.ticketing.seat.domain.SeatMapSnapshot.Section;
import com.example.ticketing.seat.repository.SeatRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 회차 좌석을 쿼리 한 번으로 읽어 좌석맵 JSON을 만든다
@Component
@RequiredArgsConstructor
public class SeatMapBuilder {

    private final SeatRepository seatRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public String build(long performanceId) {
        return json(snapshot(performanceId));
    }

    public SeatMapSnapshot snapshot(long performanceId) {
        List<Section> sections = new ArrayList<>();
        for (SeatMapSeat seat : seatRepository.findSeatMap(performanceId)) {
            Section lastSection = sections.isEmpty() ? null : sections.get(sections.size() - 1);
            if (lastSection == null || !lastSection.section().equals(seat.section())) {
                lastSection = new Section(seat.section(), new ArrayList<>());
                sections.add(lastSection);
            }
            List<Row> rows = lastSection.rows();
            Row lastRow = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (lastRow == null || lastRow.rowNo() != seat.rowNo()) {
                lastRow = new Row(seat.rowNo(), new ArrayList<>());
                rows.add(lastRow);
            }
            lastRow.seats().add(new SeatCell(seat.id(), seat.seatNo(), seat.grade().name(), seat.price(),
                    SeatMapSnapshot.code(seat.status())));
        }
        // 화면이 새 스냅샷인지 가리는 값일 뿐이라 DB 시각을 읽지 않고 서버 벽시계를 쓴다
        return new SeatMapSnapshot(performanceId, clock.millis(), sections);
    }

    public String json(SeatMapSnapshot snapshot) {
        return jsonMapper.writeValueAsString(snapshot);
    }
}
