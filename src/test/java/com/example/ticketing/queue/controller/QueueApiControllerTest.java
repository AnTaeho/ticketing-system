package com.example.ticketing.queue.controller;

import static com.example.ticketing.support.TestMvc.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import com.example.ticketing.support.TestRedis;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class QueueApiControllerTest {

    private static final String TOKENS = "/api/performances/{pid}/queue/tokens";
    private static final String TOKEN = "/api/performances/{pid}/queue/tokens/{token}";
    private static final String HEARTBEAT = TOKEN + "/heartbeat";
    private static final String LEAVE = TOKEN + "/leave";

    @Autowired
    private TestMvc testMvc;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestRedis testRedis;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @Test
    @DisplayName("진입 → 조회 → 재진입 흐름에서 같은 토큰으로 처리열 상태를 돌려준다")
    void enterAndStatus() throws Exception {
        long pid = openPerformance();
        long userId = fixtures.newUser();

        JsonNode entered = testMvc.body(mockMvc.perform(post(TOKENS, pid).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.token").isString())
                .andReturn());
        String token = entered.get("token").asString();
        assertThat(token).startsWith(userId + ".");

        mockMvc.perform(get(TOKEN, pid, token).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(0));

        mockMvc.perform(post(TOKENS, pid).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token));
    }

    @Test
    @DisplayName("처리열이 차 있으면 대기열 1번과 예상 대기 시간을 돌려준다")
    void waitingFormat() throws Exception {
        long pid = openPerformance();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            testRedis.template().opsForZSet().add(RedisKeys.queueProcessing(pid), "filler." + i, now + 600_000);
        }
        long userId = fixtures.newUser();

        String token = testMvc.body(mockMvc.perform(post(TOKENS, pid).header(USER, userId))
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
    @DisplayName("남의 토큰을 조회하면 404, 예매 전 회차에 들어오면 409로 답한다")
    void errors() throws Exception {
        long pid = openPerformance();
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        String token = testMvc.body(mockMvc.perform(post(TOKENS, pid).header(USER, owner)).andReturn())
                .get("token").asString();

        mockMvc.perform(get(TOKEN, pid, token).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_NOT_FOUND"));

        LocalDateTime now = fixtures.dbNow();
        long notOpen = fixtures.newPerformance(fixtures.newShow(), now.plusHours(1), now.plusDays(1), 1, 1, 1);

        mockMvc.perform(post(TOKENS, notOpen).header(USER, owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_OPEN"));
    }

    @Test
    @DisplayName("생존 신호는 살아 있으면 남은 시간을, 만료면 403, 남의 토큰이면 404로 답한다")
    void heartbeat() throws Exception {
        long pid = openPerformance();
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        String token = testMvc.body(mockMvc.perform(post(TOKENS, pid).header(USER, owner)).andReturn())
                .get("token").asString();

        mockMvc.perform(post(HEARTBEAT, pid, token).header(USER, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.expiresInMs").value(120_000));

        mockMvc.perform(post(HEARTBEAT, pid, token).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_NOT_FOUND"));

        testRedis.template().opsForZSet().add(RedisKeys.queueProcessing(pid), token, System.currentTimeMillis() - 1);
        mockMvc.perform(post(HEARTBEAT, pid, token).header(USER, owner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));
    }

    @Test
    @DisplayName("떠남 신호는 본문 없이 받아 204로 답하고, 남의 토큰이면 404다")
    void leave() throws Exception {
        long pid = openPerformance();
        long owner = fixtures.newUser();
        long stranger = fixtures.newUser();
        String token = testMvc.body(mockMvc.perform(post(TOKENS, pid).header(USER, owner)).andReturn())
                .get("token").asString();

        mockMvc.perform(post(LEAVE, pid, token).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_NOT_FOUND"));
        long before = System.currentTimeMillis();
        mockMvc.perform(post(LEAVE, pid, token).header(USER, owner))
                .andExpect(status().isNoContent());
        long after = System.currentTimeMillis();

        assertThat(testRedis.template().opsForZSet().score(RedisKeys.queueProcessing(pid), token))
                .isBetween(before + 10_000.0, after + 10_000.0);
        mockMvc.perform(post(LEAVE, pid, owner + ".no-such-token").header(USER, owner))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("사용자 식별이 없으면 진입·조회 모두 401 LOGIN_REQUIRED로 답한다")
    void loginRequired() throws Exception {
        mockMvc.perform(post(TOKENS, 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mockMvc.perform(get(TOKEN, 1L, "1.abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mockMvc.perform(post(HEARTBEAT, 1L, "1.abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mockMvc.perform(post(LEAVE, 1L, "1.abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    private long openPerformance() {
        return fixtures.newPerformance(1, 1, 1);
    }
}
