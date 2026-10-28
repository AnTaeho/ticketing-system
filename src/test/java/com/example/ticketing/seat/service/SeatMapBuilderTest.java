package com.example.ticketing.seat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class SeatMapBuilderTest {

    @Autowired
    private SeatMapBuilder seatMapBuilder;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("스냅샷은 좌석을 구역·열·번호 순서로 묶어 담는다")
    void buildsNestedSnapshot() {
        long pid = fixtures.newPerformance(2, 3, 4);
        long before = System.currentTimeMillis();

        String json = seatMapBuilder.build(pid).orElseThrow();

        JsonNode root = jsonMapper.readTree(json);
        assertThat(root.get("performanceId").asLong()).isEqualTo(pid);
        assertThat(root.get("generatedAt").asLong()).isBetween(before, System.currentTimeMillis());
        JsonNode sections = root.get("sections");
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).get("section").asString()).isEqualTo("A");
        assertThat(sections.get(1).get("section").asString()).isEqualTo("B");
        for (JsonNode section : sections) {
            JsonNode rows = section.get("rows");
            assertThat(rows).hasSize(3);
            for (int r = 0; r < 3; r++) {
                JsonNode row = rows.get(r);
                assertThat(row.get("rowNo").asInt()).isEqualTo(r + 1);
                JsonNode seats = row.get("seats");
                assertThat(seats).hasSize(4);
                for (int n = 0; n < 4; n++) {
                    JsonNode seat = seats.get(n);
                    assertThat(seat.get("no").asInt()).isEqualTo(n + 1);
                    assertThat(seat.get("id").asLong()).isPositive();
                    assertThat(seat.get("grade").asString()).isEqualTo(r < 2 ? "VIP" : "R");
                    assertThat(seat.get("price").asInt()).isEqualTo(r < 2 ? 180_000 : 150_000);
                    assertThat(seat.get("s").asString()).isEqualTo("A");
                }
            }
        }
    }

    @Test
    @DisplayName("선점한 좌석은 H, 확정한 좌석은 S로 다음 빌드에 나온다")
    void reflectsSeatStatus() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> seatIds = db.seatIdsInLayoutOrder(pid);

        assertThat(statuses(seatMapBuilder.build(pid).orElseThrow())).containsExactly("A", "A", "A", "A");

        jdbcTemplate.update("UPDATE seat SET status = 'HELD', held_until = NOW(6) + INTERVAL 5 MINUTE WHERE id = ?",
                seatIds.get(1));
        jdbcTemplate.update("UPDATE seat SET status = 'SOLD' WHERE id = ?", seatIds.get(3));

        assertThat(statuses(seatMapBuilder.build(pid).orElseThrow())).containsExactly("A", "H", "A", "S");
    }

    @Test
    @DisplayName("좌석이 없는 회차면 빈 결과를 돌려준다")
    void emptyWhenNoSeats() {
        assertThat(seatMapBuilder.build(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("800석 회차의 스냅샷은 800석을 모두 담는다")
    void buildsAll800Seats() {
        long pid = fixtures.newPerformance(4, 10, 20);

        String json = seatMapBuilder.build(pid).orElseThrow();

        assertThat(statuses(json)).hasSize(800);
    }

    private List<String> statuses(String json) {
        List<String> result = new ArrayList<>();
        for (JsonNode section : jsonMapper.readTree(json).get("sections")) {
            for (JsonNode row : section.get("rows")) {
                for (JsonNode seat : row.get("seats")) {
                    result.add(seat.get("s").asString());
                }
            }
        }
        return result;
    }
}
