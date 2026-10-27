package com.example.ticketing.booking.hold.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@BookingIntegrationTest
class HoldApiControllerTest {

    private static final String USER = "X-User-Id";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private MeterRegistry meterRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("POST 201 → GET 상태 → DELETE 204 → GET RELEASED")
    void holdGetRelease() throws Exception {
        long pid = fixtures.newPerformance(1, 10, 2);
        List<Long> ids = seatIds(pid);
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
        JsonNode body = body(created);
        System.out.println("[S2.1] POST /holds 응답: " + body);
        long holdId = body.get("holdId").asLong();

        JsonNode detail = body(mockMvc.perform(get("/api/holds/{holdId}", holdId).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
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
        System.out.println("[S2.1] GET /holds/{id} 응답: " + detail);
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
                .andExpect(jsonPath("$.code").value("HOLD_NOT_CANCELLABLE"));
    }

    @Test
    @DisplayName("남의 hold와 없는 hold는 조회·취소 모두 404 HOLD_NOT_FOUND")
    void othersHoldIsNotFound() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 2);
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        long holdId = body(hold(owner, pid, List.of(seatIds(pid).get(0)))
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
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId))
                .isEqualTo("HELD");
    }

    @Test
    @DisplayName("결제 중(PAYING)인 hold는 취소할 수 없다: 409 HOLD_NOT_CANCELLABLE, 좌석 유지")
    void payingHoldNotCancellable() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 2);
        long seatId = seatIds(pid).get(0);
        long userId = fixtures.newUser();
        long holdId = body(hold(userId, pid, List.of(seatId))
                .andExpect(status().isCreated()).andReturn()).get("holdId").asLong();
        jdbcTemplate.update("UPDATE seat_hold SET status = 'PAYING' WHERE id = ?", holdId);

        mockMvc.perform(delete("/api/holds/{holdId}", holdId).header(USER, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.detail.status").value("PAYING"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seatId))
                .isEqualTo("HELD");
    }

    @Test
    @DisplayName("두 번째 선점은 409 USER_HAS_HOLD, 이미 잡힌 좌석은 게이트가 409 SEAT_TAKEN(detail.seatId)")
    void conflicts() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> ids = seatIds(pid);
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
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, ids.get(1)))
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("다른 회차 좌석·없는 좌석·5석은 400 INVALID_SEAT_SELECTION이고 booking.hold.db가 늘지 않는다")
    void invalidSelectionDoesNotReachDb() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 6);
        long otherPid = fixtures.newPerformance(1, 1, 2);
        List<Long> ids = seatIds(pid);
        long userId = fixtures.newUser();
        double before = dbCounterTotal();

        hold(userId, pid, List.of(ids.get(0), seatIds(otherPid).get(0)))
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

        assertThat(dbCounterTotal()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE user_id = ?",
                Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("사용자 식별이 없으면 401 LOGIN_REQUIRED")
    void loginRequired() throws Exception {
        mockMvc.perform(post("/api/performances/{pid}/holds", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    private ResultActions hold(long userId, long pid, List<Long> seatIds) throws Exception {
        String ids = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return mockMvc.perform(post("/api/performances/{pid}/holds", pid)
                .header(USER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatIds\":[%s],\"queueToken\":\"unchecked\"}".formatted(ids)));
    }

    private double dbCounterTotal() {
        return meterRegistry.find("booking.hold.db").counters().stream().mapToDouble(Counter::count).sum();
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }
}
