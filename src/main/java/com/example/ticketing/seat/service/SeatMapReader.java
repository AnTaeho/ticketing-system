package com.example.ticketing.seat.service;

import com.example.ticketing.seat.domain.SeatMapSeat;
import com.example.ticketing.seat.dto.SeatMapResult;
import com.example.ticketing.seat.dto.SeatMapResult.Row;
import com.example.ticketing.seat.dto.SeatMapResult.SeatCell;
import com.example.ticketing.seat.dto.SeatMapResult.Section;
import com.example.ticketing.seat.repository.SeatRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 좌석 화면이 부를 때마다 회차 좌석을 쿼리 한 번으로 읽어 구역·열·번호 순으로 묶는다. 회차가 없으면 404다
@Service
@RequiredArgsConstructor
public class SeatMapReader {

    private final SeatCatalog seatCatalog;
    private final SeatRepository seatRepository;
    private final Clock clock;

    public SeatMapResult read(long performanceId) {
        seatCatalog.performance(performanceId);
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
                    SeatMapResult.code(seat.status())));
        }
        // 화면이 같은 응답인지 가리는 값이라 DB 시각을 읽지 않고 서버 시계로 충분하다
        return new SeatMapResult(performanceId, clock.millis(), sections);
    }
}
