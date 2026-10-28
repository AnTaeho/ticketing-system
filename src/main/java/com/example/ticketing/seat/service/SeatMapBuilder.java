package com.example.ticketing.seat.service;

import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.seat.domain.SeatMapRow;
import com.example.ticketing.seat.domain.SeatMapSnapshot;
import com.example.ticketing.seat.domain.SeatMapSnapshot.Row;
import com.example.ticketing.seat.domain.SeatMapSnapshot.SeatCell;
import com.example.ticketing.seat.domain.SeatMapSnapshot.Section;
import com.example.ticketing.seat.repository.SeatJdbcRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 회차 좌석을 쿼리 한 번으로 읽어 스냅샷 JSON을 만든다. 재생성 카운터는 출처를 아는 호출자가 올린다
@Component
@RequiredArgsConstructor
public class SeatMapBuilder {

    private final SeatJdbcRepository seatJdbcRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    // 좌석이 없으면 빈 Optional. 빈 좌석맵은 저장하거나 내보내지 않는다
    public Optional<String> build(long performanceId) {
        List<Section> sections = new ArrayList<>();
        for (SeatMapRow seat : seatJdbcRepository.findSeatMapRows(performanceId)) {
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
            lastRow.seats().add(new SeatCell(seat.id(), seat.seatNo(), seat.grade(), seat.price(),
                    SeatMapSnapshot.code(seat.status())));
        }
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        // 화면이 새 스냅샷인지 가리는 값일 뿐이라 DB 시각을 읽지 않고 서버 벽시계를 쓴다
        SeatMapSnapshot snapshot = new SeatMapSnapshot(performanceId, clock.millis(), sections);
        return Optional.of(jsonMapper.writeValueAsString(snapshot));
    }

    // source는 Metrics.RebuildSource 값이다
    static Counter rebuildCounter(MeterRegistry meterRegistry, String source) {
        return meterRegistry.counter(Metrics.SEATMAP_REBUILD, Tag.SOURCE, source);
    }
}
