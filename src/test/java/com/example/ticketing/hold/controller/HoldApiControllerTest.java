package com.example.ticketing.hold.controller;

import static com.example.ticketing.common.web.HttpNames.USER_ID_HEADER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestMvc;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

// 판정은 SeatHoldServiceTest·SeatCatalogTest가 본다. 여기서는 HTTP 연결만 본다
@IntegrationTest
class HoldApiControllerTest {

    @Autowired
    private TestMvc testMvc;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @Test
    @DisplayName("잡기는 201, 선점·내 선점 조회는 200 JSON, 놓기는 본문 없는 204로 답한다")
    void responseShapes() throws Exception {
        long pid = fixtures.newPerformance(1, 10, 2);
        List<Long> ids = db.seatIds(pid);
        long vip = ids.get(0);
        long r = ids.get(4);
        long userId = fixtures.newUser();

        JsonNode created = testMvc.body(hold(userId, pid, List.of(r, vip))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").isNumber())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.totalAmount").value(180_000 + 150_000))
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.seats[0].id").value(vip))
                .andExpect(jsonPath("$.seats[0].section").value("A"))
                .andExpect(jsonPath("$.seats[0].rowNo").value(1))
                .andExpect(jsonPath("$.seats[0].seatNo").value(1))
                .andExpect(jsonPath("$.seats[0].grade").value("VIP"))
                .andExpect(jsonPath("$.seats[0].price").value(180_000))
                .andExpect(jsonPath("$.seats[1].id").value(r))
                .andExpect(jsonPath("$.seats[1].grade").value("R"))
                .andReturn());
        long holdId = created.get("holdId").asLong();
        String expiresAt = created.get("expiresAt").asString();

        mockMvc.perform(get("/api/holds/{holdId}", holdId).header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.performanceId").value(pid))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt))
                .andExpect(jsonPath("$.payDeadline").isEmpty())
                .andExpect(jsonPath("$.serverNow").isString())
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.seats[0].id").value(vip))
                .andExpect(jsonPath("$.seats[1].id").value(r))
                .andExpect(jsonPath("$.totalAmount").value(330_000))
                .andExpect(jsonPath("$.paymentId").isEmpty());
        mockMvc.perform(get("/api/performances/{pid}/holds/mine", pid).header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt));

        mockMvc.perform(delete("/api/holds/{holdId}", holdId).header(USER_ID_HEADER, userId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("고른 좌석 수가 잘못됐으면 400 INVALID_SEAT_SELECTION으로 답한다")
    void invalidSelection() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 6);
        long userId = fixtures.newUser();

        hold(userId, pid, db.seatIds(pid).subList(0, 5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEAT_SELECTION"))
                .andExpect(jsonPath("$.detail.reason").value("COUNT"));
    }

    @Test
    @DisplayName("사용자 식별이 없으면 잡기와 내 선점 조회 모두 401 LOGIN_REQUIRED로 답한다")
    void loginRequired() throws Exception {
        mockMvc.perform(post("/api/performances/{pid}/holds", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mockMvc.perform(get("/api/performances/{pid}/holds/mine", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    private ResultActions hold(long userId, long pid, List<Long> seatIds) throws Exception {
        String ids = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String body = "{\"seatIds\":[%s]}".formatted(ids);
        return mockMvc.perform(post("/api/performances/{pid}/holds", pid)
                .header(USER_ID_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
