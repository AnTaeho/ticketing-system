package com.example.ticketing.booking.seat.map;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@BookingIntegrationTest
class SeatMapBuilderTest {

    @Autowired
    private SeatMapBuilder builder;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("구역 → 열 → 좌석 번호 순서로 묶고, 좌석마다 id·no·grade·price·s를 담는다")
    void buildsNestedSnapshot() {
        long pid = fixtures.newPerformance(2, 3, 4);
        long before = System.currentTimeMillis();

        String json = builder.build(pid).orElseThrow();

        System.out.println("[S7.1] 2×3×4 스냅샷 앞부분: " + json.substring(0, Math.min(300, json.length())));
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
        List<Long> seatIds = jdbcTemplate.queryForList(
                "SELECT id FROM seat WHERE performance_id = ? ORDER BY seat_no", Long.class, pid);
        assertThat(statuses(builder.build(pid).orElseThrow())).containsExactly("A", "A", "A", "A");

        jdbcTemplate.update("UPDATE seat SET status = 'HELD', held_until = NOW(6) + INTERVAL 5 MINUTE WHERE id = ?",
                seatIds.get(1));
        jdbcTemplate.update("UPDATE seat SET status = 'SOLD' WHERE id = ?", seatIds.get(3));

        assertThat(statuses(builder.build(pid).orElseThrow())).containsExactly("A", "H", "A", "S");
    }

    @Test
    @DisplayName("좌석이 없는 회차는 빈 Optional")
    void emptyWhenNoSeats() {
        assertThat(builder.build(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("800석 스냅샷의 JSON 크기와 빌드 시간을 잰다")
    void measures800Seats() {
        long pid = fixtures.newPerformance(4, 10, 20);
        builder.build(pid).orElseThrow();

        int runs = 20;
        long totalNanos = 0;
        long maxNanos = 0;
        String json = null;
        for (int i = 0; i < runs; i++) {
            long started = System.nanoTime();
            json = builder.build(pid).orElseThrow();
            long elapsed = System.nanoTime() - started;
            totalNanos += elapsed;
            maxNanos = Math.max(maxNanos, elapsed);
        }

        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        assertThat(statuses(json)).hasSize(800);
        System.out.printf("[S7.1] 800석 스냅샷: %d bytes, 빌드 평균 %.2f ms, 최대 %.2f ms (예열 1회 뒤 %d회)%n",
                bytes, totalNanos / 1e6 / runs, maxNanos / 1e6, runs);
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
