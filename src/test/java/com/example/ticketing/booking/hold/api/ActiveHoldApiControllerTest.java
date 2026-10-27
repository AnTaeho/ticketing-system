package com.example.ticketing.booking.hold.api;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@BookingIntegrationTest
class ActiveHoldApiControllerTest {

    private static final String USER = "X-User-Id";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("잡아 둔 자리가 없으면 404 HOLD_NOT_FOUND, 잡으면 200 holdId·status·expiresAt")
    void returnsMyActiveHold() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 4);
        long userId = fixtures.newUser();

        mine(userId, pid)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));

        long holdId = hold(userId, pid, seatIds(pid).get(0));
        String expiresAt = jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(expires_at, '%Y-%m-%dT%H:%i:%s') FROM seat_hold WHERE id = ?", String.class, holdId);

        mine(userId, pid)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.expiresAt").value(startsWith(expiresAt)));
    }

    @Test
    @DisplayName("결제 중(PAYING)인 선점도 돌려준다")
    void includesPaying() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 2);
        long userId = fixtures.newUser();
        long holdId = hold(userId, pid, seatIds(pid).get(0));
        jdbcTemplate.update("UPDATE seat_hold SET status = 'PAYING' WHERE id = ?", holdId);

        mine(userId, pid)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.status").value("PAYING"));
    }

    @Test
    @DisplayName("놓은 선점, 남의 선점, 다른 회차의 선점은 404 HOLD_NOT_FOUND")
    void ignoresInactiveAndOthers() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 4);
        long otherPid = fixtures.newPerformance(1, 1, 2);
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        long holdId = hold(owner, pid, seatIds(pid).get(0));

        mine(stranger, pid)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
        mine(owner, otherPid)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));

        mockMvc.perform(delete("/api/holds/{holdId}", holdId).header(USER, owner))
                .andExpect(status().isNoContent());
        mine(owner, pid)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }

    @Test
    @DisplayName("사용자 식별이 없으면 401 LOGIN_REQUIRED")
    void loginRequired() throws Exception {
        mockMvc.perform(get("/api/performances/{pid}/holds/mine", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    private ResultActions mine(long userId, long pid) throws Exception {
        return mockMvc.perform(get("/api/performances/{pid}/holds/mine", pid).header(USER, userId));
    }

    private long hold(long userId, long pid, long seatId) throws Exception {
        String body = "{\"seatIds\":[%d],\"queueToken\":\"%s\"}".formatted(seatId, fixtures.admit(pid, userId));
        String response = mockMvc.perform(post("/api/performances/{pid}/holds", pid)
                        .header(USER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(response).get("holdId").asLong();
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }
}
