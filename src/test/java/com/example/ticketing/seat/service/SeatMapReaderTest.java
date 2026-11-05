package com.example.ticketing.seat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.seat.dto.SeatMapResult;
import com.example.ticketing.seat.dto.SeatMapResult.Row;
import com.example.ticketing.seat.dto.SeatMapResult.SeatCell;
import com.example.ticketing.seat.dto.SeatMapResult.Section;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SeatMapReaderTest {

    @Autowired
    private SeatMapReader seatMapReader;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("좌석맵은 좌석을 구역·열·번호 순서로 묶어 담는다")
    void readsNestedSeatMap() {
        long pid = fixtures.newPerformance(2, 3, 4);
        long before = System.currentTimeMillis();

        SeatMapResult seatMap = seatMapReader.read(pid);

        assertThat(seatMap.performanceId()).isEqualTo(pid);
        assertThat(seatMap.generatedAt()).isBetween(before, System.currentTimeMillis());
        List<Section> sections = seatMap.sections();
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).section()).isEqualTo("A");
        assertThat(sections.get(1).section()).isEqualTo("B");
        for (Section section : sections) {
            List<Row> rows = section.rows();
            assertThat(rows).hasSize(3);
            for (int r = 0; r < 3; r++) {
                Row row = rows.get(r);
                assertThat(row.rowNo()).isEqualTo(r + 1);
                assertThat(row.seats()).hasSize(4);
                for (int n = 0; n < 4; n++) {
                    SeatCell seat = row.seats().get(n);
                    assertThat(seat.no()).isEqualTo(n + 1);
                    assertThat(seat.id()).isPositive();
                    assertThat(seat.grade()).isEqualTo(r < 2 ? "VIP" : "R");
                    assertThat(seat.price()).isEqualTo(r < 2 ? 180_000 : 150_000);
                    assertThat(seat.s()).isEqualTo("A");
                }
            }
        }
    }

    @Test
    @DisplayName("선점한 좌석은 H, 확정한 좌석은 S로 다음 조회에 나온다")
    void reflectsSeatStatus() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> seatIds = db.seatIdsInLayoutOrder(pid);

        assertThat(statuses(seatMapReader.read(pid))).containsExactly("A", "A", "A", "A");

        jdbcTemplate.update("UPDATE seat SET status = 'HELD' WHERE seat_id = ?",
                seatIds.get(1));
        jdbcTemplate.update("UPDATE seat SET status = 'SOLD' WHERE seat_id = ?", seatIds.get(3));

        assertThat(statuses(seatMapReader.read(pid))).containsExactly("A", "H", "A", "S");
    }

    @Test
    @DisplayName("800석 회차의 좌석맵은 800석을 모두 담는다")
    void readsAll800Seats() {
        long pid = fixtures.newPerformance(4, 10, 20);

        assertThat(statuses(seatMapReader.read(pid))).hasSize(800);
    }

    private List<String> statuses(SeatMapResult seatMap) {
        return seatMap.sections().stream()
                .flatMap(section -> section.rows().stream())
                .flatMap(row -> row.seats().stream())
                .map(SeatCell::s)
                .toList();
    }
}
