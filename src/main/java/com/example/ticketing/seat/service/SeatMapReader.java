package com.example.ticketing.seat.service;

import com.example.ticketing.seat.domain.Seat;
import com.example.ticketing.seat.dto.SeatMapResult;
import com.example.ticketing.seat.dto.SeatMapResult.Row;
import com.example.ticketing.seat.dto.SeatMapResult.SeatCell;
import com.example.ticketing.seat.dto.SeatMapResult.Section;
import com.example.ticketing.seat.repository.SeatRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

// 좌석 화면이 부를 때마다 회차 좌석을 쿼리 한 번으로 읽어 구역·열·번호 순으로 묶는다. 회차가 없으면 404다
@Component
@RequiredArgsConstructor
public class SeatMapReader {

    private static final Sort SEAT_MAP_ORDER = Sort.by("section", "rowNo", "seatNo");

    private final SeatCatalog seatCatalog;
    private final SeatRepository seatRepository;
    private final Clock clock;

    public SeatMapResult read(long performanceId) {
        seatCatalog.performance(performanceId);
        List<Section> sections = new ArrayList<>();
        for (Seat seat : seatRepository.findByPerformanceId(performanceId, SEAT_MAP_ORDER)) {
            Section lastSection = sections.isEmpty() ? null : sections.get(sections.size() - 1);
            if (lastSection == null || !lastSection.section().equals(seat.getSection())) {
                lastSection = new Section(seat.getSection(), new ArrayList<>());
                sections.add(lastSection);
            }
            List<Row> rows = lastSection.rows();
            Row lastRow = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (lastRow == null || lastRow.rowNo() != seat.getRowNo()) {
                lastRow = new Row(seat.getRowNo(), new ArrayList<>());
                rows.add(lastRow);
            }
            lastRow.seats().add(new SeatCell(seat.getId(), seat.getSeatNo(), seat.getGrade().name(), seat.getPrice(),
                    SeatMapResult.code(seat.getStatus())));
        }
        // 화면이 같은 응답인지 가리는 값이라 서버 시계로 충분하다
        return new SeatMapResult(performanceId, clock.millis(), sections);
    }
}
