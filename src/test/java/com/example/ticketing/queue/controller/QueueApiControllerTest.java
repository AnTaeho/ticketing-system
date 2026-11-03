package com.example.ticketing.queue.controller;

import static com.example.ticketing.common.web.HttpNames.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 대기열 판정은 QueueServiceTest·QueuePromotionSchedulerTest가 본다. 여기서는 HTTP 연결만 본다
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @Test
    @DisplayName("진입·순서 조회·생존 신호는 200 JSON으로, 떠남 신호는 본문 없는 204로 답한다")
    void responseShapes() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 1);
        long userId = fixtures.newUser();

        String token = testMvc.body(mockMvc.perform(post(TOKENS, pid).header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.token").isString())
                .andReturn()).get("token").asString();
        assertThat(token).startsWith(userId + ".");

        mockMvc.perform(get(TOKEN, pid, token).header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(0));
        mockMvc.perform(post(HEARTBEAT, pid, token).header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.expiresInMs").value(120_000));

        mockMvc.perform(post(LEAVE, pid, token).header(USER_ID_HEADER, userId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(post(LEAVE, pid, userId + ".no-such-token").header(USER_ID_HEADER, userId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("남의 토큰으로 순서를 물으면 404 QUEUE_TOKEN_NOT_FOUND로 답한다")
    void foreignToken() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 1);
        long owner = fixtures.newUser();
        String token = testMvc.body(mockMvc.perform(post(TOKENS, pid).header(USER_ID_HEADER, owner)).andReturn())
                .get("token").asString();

        mockMvc.perform(get(TOKEN, pid, token).header(USER_ID_HEADER, fixtures.newUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_NOT_FOUND"));
    }

    @Test
    @DisplayName("사용자 식별이 없으면 진입·조회·생존 신호·떠남 모두 401 LOGIN_REQUIRED로 답한다")
    void loginRequired() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{
                post(TOKENS, 1L), get(TOKEN, 1L, "1.abc"), post(HEARTBEAT, 1L, "1.abc"), post(LEAVE, 1L, "1.abc")}) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        }
    }
}
