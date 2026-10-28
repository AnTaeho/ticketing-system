package com.example.ticketing.hold.controller;

import static com.example.ticketing.support.TestMvc.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestMvc;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class HoldApiControllerTest {

    @Autowired
    private TestMvc testMvc;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private Counters counters;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @Test
    @DisplayName("잡기 → 조회 → 취소 → 조회 흐름에서 상태가 RELEASED로 바뀐다")
    void holdGetRelease() throws Exception {
        long pid = fixtures.newPerformance(1, 10, 2);
        List<Long> ids = db.seatIds(pid);
        long vip = ids.get(0);
        long r = ids.get(4);
        long userId = fixtures.newUser();

        MvcResult created = hold(userId, pid, List.of(r, vip))
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
                .andReturn();
        JsonNode body = testMvc.body(created);
        long holdId = body.get("holdId").asLong();

        JsonNode detail = testMvc.body(mockMvc.perform(get("/api/holds/{holdId}", holdId).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.performanceId").value(pid))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.expiresAt").value(body.get("expiresAt").asString()))
                .andExpect(jsonPath("$.payDeadline").isEmpty())
                .andExpect(jsonPath("$.serverNow").isString())
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.seats[0].id").value(vip))
                .andExpect(jsonPath("$.seats[1].id").value(r))
                .andExpect(jsonPath("$.totalAmount").value(330_000))
                .andExpect(jsonPath("$.paymentId").isEmpty())
                .andReturn());
        assertThat(detail.has("payDeadline")).isTrue();
        assertThat(detail.has("paymentId")).isTrue();
        LocalDateTime serverNow = LocalDateTime.parse(detail.get("serverNow").asString());
        assertThat(serverNow).isBefore(LocalDateTime.parse(body.get("expiresAt").asString()));

        mockMvc.perform(delete("/api/holds/{holdId}", holdId).header(USER, userId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/holds/{holdId}", holdId).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.seats.length()").value(0));

        mockMvc.perform(delete("/api/holds/{holdId}", holdId).header(USER, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.detail.status").value("RELEASED"));
    }

    @Test
    @DisplayName("남의 선점이나 없는 선점은 조회·취소 모두 404로 답한다")
    void othersHoldIsNotFound() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 2);
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        long holdId = testMvc.body(hold(owner, pid, List.of(db.seatIds(pid).get(0)))
                .andExpect(status().isCreated()).andReturn()).get("holdId").asLong();

        mockMvc.perform(get("/api/holds/{holdId}", holdId).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        mockMvc.perform(delete("/api/holds/{holdId}", holdId).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        mockMvc.perform(get("/api/holds/{holdId}", Long.MAX_VALUE).header(USER, owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        assertThat(db.holdStatus(holdId)).isEqualTo("HELD");
    }

    @Test
    @DisplayName("결제 중인 선점을 취소하면 409로 거절하고 좌석을 그대로 둔다")
    void payingHoldNotCancellable() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 2);
        long seatId = db.seatIds(pid).get(0);
        long userId = fixtures.newUser();
        long holdId = testMvc.body(hold(userId, pid, List.of(seatId))
                .andExpect(status().isCreated()).andReturn()).get("holdId").asLong();
        jdbcTemplate.update("UPDATE seat_hold SET status = 'PAYING' WHERE id = ?", holdId);

        mockMvc.perform(delete("/api/holds/{holdId}", holdId).header(USER, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.detail.status").value("PAYING"));
        assertThat(db.seatStatus(seatId)).isEqualTo("HELD");
    }

    @Test
    @DisplayName("이미 끝난 선점을 취소하면 409로 거절하고 끝난 상태를 알려 준다")
    void notCancellableCarriesStatus() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 3);
        List<String> finishedStates = List.of("EXPIRED", "CONFIRMED", "RELEASED");
        for (int i = 0; i < finishedStates.size(); i++) {
            String finished = finishedStates.get(i);
            long userId = fixtures.newUser();
            long holdId = testMvc.body(hold(userId, pid, List.of(db.seatIds(pid).get(i)))
                    .andExpect(status().isCreated()).andReturn()).get("holdId").asLong();
            jdbcTemplate.update("UPDATE seat_hold SET status = ? WHERE id = ?", finished, holdId);

            mockMvc.perform(delete("/api/holds/{holdId}", holdId).header(USER, userId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("HOLD_NOT_CANCELLABLE"))
                    .andExpect(jsonPath("$.message").value("지금은 이 자리를 놓을 수 없어요"))
                    .andExpect(jsonPath("$.detail.status").value(finished));
            assertThat(db.holdStatus(holdId)).isEqualTo(finished);
        }
    }

    @Test
    @DisplayName("두 번째 선점이나 이미 잡힌 좌석을 고르면 409로 거절한다")
    void conflicts() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = db.seatIds(pid);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        hold(userA, pid, List.of(ids.get(0))).andExpect(status().isCreated());

        hold(userA, pid, List.of(ids.get(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_HAS_HOLD"));
        hold(userB, pid, List.of(ids.get(0), ids.get(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_TAKEN"))
                .andExpect(jsonPath("$.detail.seatId").value(ids.get(0)));
        assertThat(db.seatStatus(ids.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("고른 좌석이 잘못됐으면 DB까지 가지 않고 400으로 거절한다")
    void invalidSelectionDoesNotReachDb() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 6);
        long otherPid = fixtures.newPerformance(1, 1, 2);
        List<Long> ids = db.seatIds(pid);
        long userId = fixtures.newUser();
        double before = counters.sum(Metrics.HOLD_DB);

        hold(userId, pid, List.of(ids.get(0), db.seatIds(otherPid).get(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEAT_SELECTION"))
                .andExpect(jsonPath("$.detail.reason").value("UNKNOWN_SEAT"));
        hold(userId, pid, List.of(ids.get(0), Long.MAX_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEAT_SELECTION"))
                .andExpect(jsonPath("$.detail.reason").value("UNKNOWN_SEAT"));
        hold(userId, pid, ids.subList(0, 5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEAT_SELECTION"))
                .andExpect(jsonPath("$.detail.reason").value("COUNT"));

        assertThat(counters.sum(Metrics.HOLD_DB)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE user_id = ?",
                Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("처리열 토큰이 없거나 남의 것이면 403 NOT_ADMITTED로 거절한다")
    void notAdmitted() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 2);
        long userId = fixtures.newUser();
        long other = fixtures.newUser();
        List<Long> target = List.of(db.seatIds(pid).get(0));

        hold(userId, pid, target, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"))
                .andExpect(jsonPath("$.message").value("입장 순서가 지났어요. 다시 줄을 서 주세요"));
        hold(userId, pid, target, fixtures.admit(pid, other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));
        assertThat(db.holdCount(pid)).isZero();
    }

    @Test
    @DisplayName("사용자 식별이 없으면 401 LOGIN_REQUIRED로 답한다")
    void loginRequired() throws Exception {
        mockMvc.perform(post("/api/performances/{pid}/holds", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    private ResultActions hold(long userId, long pid, List<Long> seatIds) throws Exception {
        return hold(userId, pid, seatIds, fixtures.admit(pid, userId));
    }

    // queueToken이 null이면 본문에서 뺀다
    private ResultActions hold(long userId, long pid, List<Long> seatIds, String queueToken) throws Exception {
        String ids = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String body = queueToken == null
                ? "{\"seatIds\":[%s]}".formatted(ids)
                : "{\"seatIds\":[%s],\"queueToken\":\"%s\"}".formatted(ids, queueToken);
        return mockMvc.perform(post("/api/performances/{pid}/holds", pid)
                .header(USER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

}
