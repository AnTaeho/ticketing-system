package com.example.ticketing.queue.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import java.time.LocalDateTime;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class QueueApiControllerTest {

    private static final String USER = "X-User-Id";
    private static final String TOKENS = "/api/performances/{pid}/queue/tokens";
    private static final String TOKEN = "/api/performances/{pid}/queue/tokens/{token}";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private RedisKeyCleaner cleaner;
    @Autowired
    private TicketingRedis bookingRedis;
    @Autowired
    private JsonMapper jsonMapper;

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
    @DisplayName("진입 200 {token, status} → 조회 {status, position, estimatedWaitSeconds}")
    void enterAndStatus() throws Exception {
        long pid = openPerformance();
        long userId = fixtures.newUser();

        JsonNode entered = body(mockMvc.perform(post(TOKENS, pid).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.token").isString())
                .andReturn());
        System.out.println("[S6.1] POST /queue/tokens 응답: " + entered);
        String token = entered.get("token").asString();
        assertThat(token).startsWith(userId + ".");

        JsonNode position = body(mockMvc.perform(get(TOKEN, pid, token).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(0))
                .andReturn());
        System.out.println("[S6.1] GET /queue/tokens/{token} 응답: " + position);

        mockMvc.perform(post(TOKENS, pid).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token));
    }

    @Test
    @DisplayName("처리열이 차 있으면 WAITING, 순번 1, 예상 10초(rate 없음 → 0.1)")
    void waitingFormat() throws Exception {
        long pid = openPerformance();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            bookingRedis.template().opsForZSet().add(RedisKeys.queueProcessing(pid), "filler." + i, now + 600_000);
        }
        long userId = fixtures.newUser();

        String token = body(mockMvc.perform(post(TOKENS, pid).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andReturn()).get("token").asString();

        mockMvc.perform(get(TOKEN, pid, token).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(10));
    }

    @Test
    @DisplayName("남의 토큰 조회는 404 QUEUE_TOKEN_NOT_FOUND, 예매 전 회차 진입은 409 NOT_OPEN")
    void errors() throws Exception {
        long pid = openPerformance();
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        String token = body(mockMvc.perform(post(TOKENS, pid).header(USER, owner)).andReturn())
                .get("token").asString();

        mockMvc.perform(get(TOKEN, pid, token).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_NOT_FOUND"));

        LocalDateTime now = fixtures.dbNow();
        long notOpen = fixtures.newPerformance(fixtures.newShow(), now.plusHours(1), now.plusDays(1), 1, 1, 1);
        pids.add(notOpen);
        mockMvc.perform(post(TOKENS, notOpen).header(USER, owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_OPEN"));
    }

    @Test
    @DisplayName("사용자 식별이 없으면 진입·조회 모두 401 LOGIN_REQUIRED")
    void loginRequired() throws Exception {
        mockMvc.perform(post(TOKENS, 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mockMvc.perform(get(TOKEN, 1L, "1.abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    private long openPerformance() {
        long pid = fixtures.newPerformance(1, 1, 1);
        pids.add(pid);
        return pid;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }
}
