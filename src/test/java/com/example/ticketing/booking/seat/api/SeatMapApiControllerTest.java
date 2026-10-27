package com.example.ticketing.booking.seat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.booking.seat.map.SeatMapStore;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@BookingIntegrationTest
class SeatMapApiControllerTest {

    private static final String SEATS = "/api/performances/{pid}/seats";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private RedisKeyCleaner cleaner;
    @Autowired
    private SeatMapStore store;

    private MockMvc mockMvc;
    private final List<Long> pids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        pids.forEach(cleaner::clean);
    }

    @Test
    @DisplayName("200 application/json;charset=UTF-8, Cache-Control no-store, 스냅샷 형식")
    void returnsSnapshotJson() throws Exception {
        long pid = performance();

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
        System.out.println("[S7.1] GET /seats 응답: " + body);
        assertThat(store.get(pid)).isEqualTo(body);
    }

    @Test
    @DisplayName("Redis에 든 스냅샷 문자열을 바꾸지 않고 그대로 낸다")
    void writesStoredStringVerbatim() throws Exception {
        long pid = performance();
        String stored = "{\"performanceId\":" + pid + ",\"generatedAt\":42,\"sections\":[],\"note\":\"좌석\"}";
        store.put(pid, stored, Duration.ofSeconds(30));

        MvcResult result = mockMvc.perform(get(SEATS, pid))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(stored);
    }

    @Test
    @DisplayName("없는 회차는 404 PERFORMANCE_NOT_FOUND")
    void unknownPerformance() throws Exception {
        mockMvc.perform(get(SEATS, Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
    }

    private long performance() {
        long pid = fixtures.newPerformance(1, 2, 3);
        pids.add(pid);
        return pid;
    }
}
