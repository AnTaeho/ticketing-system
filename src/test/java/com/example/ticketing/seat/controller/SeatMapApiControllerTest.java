package com.example.ticketing.seat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.seat.repository.SeatMapRedisStore;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class SeatMapApiControllerTest {

    private static final String SEATS = "/api/performances/{pid}/seats";

    @Autowired
    private TestMvc testMvc;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private SeatMapRedisStore seatMapRedisStore;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @Test
    @DisplayName("좌석맵을 조회하면 캐시하지 말라는 JSON 스냅샷으로 200을 준다")
    void returnsSnapshotJson() throws Exception {
        long pid = fixtures.newPerformance(1, 2, 3);

        MvcResult result = mockMvc.perform(get(SEATS, pid))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.performanceId").value(pid))
                .andExpect(jsonPath("$.generatedAt").isNumber())
                .andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.sections[0].section").value("A"))
                .andExpect(jsonPath("$.sections[0].rows.length()").value(2))
                .andExpect(jsonPath("$.sections[0].rows[0].rowNo").value(1))
                .andExpect(jsonPath("$.sections[0].rows[0].seats.length()").value(3))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].id").isNumber())
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].no").value(1))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].grade").value("VIP"))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].price").value(180_000))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].s").value("A"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(seatMapRedisStore.get(pid)).isEqualTo(body);
    }

    @Test
    @DisplayName("Redis에 든 스냅샷 문자열을 바꾸지 않고 그대로 낸다")
    void writesStoredStringVerbatim() throws Exception {
        long pid = fixtures.newPerformance(1, 2, 3);
        String stored = "{\"performanceId\":" + pid + ",\"generatedAt\":42,\"sections\":[],\"note\":\"좌석\"}";
        seatMapRedisStore.put(pid, stored, Duration.ofSeconds(30));

        MvcResult result = mockMvc.perform(get(SEATS, pid))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(stored);
    }

    @Test
    @DisplayName("없는 회차면 404다")
    void unknownPerformance() throws Exception {
        mockMvc.perform(get(SEATS, Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
    }
}
