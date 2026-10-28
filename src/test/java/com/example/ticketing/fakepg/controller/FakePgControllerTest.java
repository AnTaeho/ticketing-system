package com.example.ticketing.fakepg.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.web.HttpNames;
import com.example.ticketing.fakepg.domain.FakePgScenario;
import com.example.ticketing.fakepg.dto.FakePgReply;
import com.example.ticketing.fakepg.service.FakePgService;
import com.example.ticketing.support.FakePgTestConfig;
import com.example.ticketing.support.FakePgTestConfig.GatedSleeper;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Concurrently.Running;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@Import(FakePgTestConfig.class)
class FakePgControllerTest {

    private static final String BASE = "/fake-pg/payments";

    @Autowired
    private TestMvc testMvc;
    @Autowired
    private FakePgService fakePgService;
    @Autowired
    private GatedSleeper sleeper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties properties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
        sleeper.reset();
    }

    @AfterEach
    void tearDown() {
        sleeper.reset();
    }

    @Test
    @DisplayName("성공 시나리오로 승인하면 승인 번호를 주고 조회·재승인도 같은 번호를 준다")
    void approveSuccess() throws Exception {
        String orderId = newOrderId();

        MvcResult approved = approve(orderId, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.pgTxId").isNotEmpty())
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andReturn();
        String pgTxId = testMvc.body(approved).get("pgTxId").asString();

        mockMvc.perform(get(BASE + "/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.pgTxId").value(pgTxId));
        approve(orderId, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pgTxId").value(pgTxId));
    }

    @Test
    @DisplayName("거절 시나리오는 대소문자와 상관없이 승인 번호 없는 거절로 답한다")
    void approveDecline() throws Exception {
        approve(newOrderId(), "decline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.pgTxId").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    @DisplayName("시나리오 헤더가 없으면 설정 비율로 무작위 결론을 낸다")
    void approveWithoutScenario() throws Exception {
        String orderId = newOrderId();

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(oneOf("APPROVED", "DECLINED")));
        assertThat(sleeper.calls()).hasSize(1);
    }

    @Test
    @DisplayName("없는 주문을 조회하면 404로 답한다")
    void queryMissing() throws Exception {
        String orderId = newOrderId();

        mockMvc.perform(get(BASE + "/" + orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.pgTxId").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("없는 주문을 취소하면 묘비가 남아 뒤늦은 승인을 거절한다")
    void cancelMissingThenApprove() throws Exception {
        String orderId = newOrderId();

        mockMvc.perform(post(BASE + "/" + orderId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.pgTxId").doesNotExist());
        approve(orderId, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.reason").value("CANCELLED"))
                .andExpect(jsonPath("$.pgTxId").doesNotExist());
        mockMvc.perform(get(BASE + "/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("승인된 주문은 몇 번 취소해도 승인 번호를 유지한 채 취소로 답한다")
    void cancelApproved() throws Exception {
        String orderId = newOrderId();
        FakePgReply approved = fakePgService.approve(orderId, 1000, FakePgScenario.SUCCESS);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(BASE + "/" + orderId + "/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.pgTxId").value(approved.pgTxId()));
        }
    }

    @Test
    @DisplayName("거절된 주문을 취소하면 거절 상태 그대로 답한다")
    void cancelDeclined() throws Exception {
        String orderId = newOrderId();
        fakePgService.approve(orderId, 1000, FakePgScenario.DECLINE);

        mockMvc.perform(post(BASE + "/" + orderId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
    }

    @Test
    @DisplayName("승인이 지연되는 동안 취소하면 409로 거절하고 승인이 끝나면 취소를 받는다")
    void cancelWhileProcessing() throws Exception {
        String orderId = newOrderId();
        sleeper.close();

        Results<FakePgReply> results;
        try (Running<FakePgReply> slow = Concurrently.of(1).within(Duration.ofSeconds(10))
                .start(i -> fakePgService.approve(orderId, 1000, FakePgScenario.SLOW_SUCCESS))) {
            try {
                assertThat(sleeper.awaitEntered(1)).isTrue();

                mockMvc.perform(post(BASE + "/" + orderId + "/cancel"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.orderId").value(orderId))
                        .andExpect(jsonPath("$.status").value("PROCESSING"))
                        .andExpect(jsonPath("$.pgTxId").doesNotExist())
                        .andExpect(jsonPath("$.code").doesNotExist());
                approve(orderId, "SLOW_SUCCESS")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("PROCESSING"));
                mockMvc.perform(get(BASE + "/" + orderId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("PROCESSING"));
            } finally {
                sleeper.open();
            }
            results = slow.await();
        }

        assertThat(results.errors()).isEmpty();
        assertThat(results.values().get(0).status()).isEqualTo("APPROVED");
        mockMvc.perform(get(BASE + "/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post(BASE + "/" + orderId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("앱이 죽어 오래 남은 처리 중 주문은 조회·취소할 때 거절로 확정한다")
    void staleProcessingIsDeclined() throws Exception {
        long staleSeconds = properties.fakePg().slowDelay().plus(properties.fakePg().staleMargin()).toSeconds() + 1;
        String staleForQuery = insertProcessing(staleSeconds);
        String staleForCancel = insertProcessing(staleSeconds);
        String recent = insertProcessing(0);

        mockMvc.perform(get(BASE + "/" + staleForQuery))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        mockMvc.perform(post(BASE + "/" + staleForCancel + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        assertThat(rowStatus(staleForQuery)).isEqualTo("DECLINED");
        assertThat(rowStatus(staleForCancel)).isEqualTo("DECLINED");
        mockMvc.perform(post(BASE + "/" + staleForQuery + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        mockMvc.perform(get(BASE + "/" + recent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        mockMvc.perform(post(BASE + "/" + recent + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(rowStatus(recent)).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("잘못된 승인·취소 요청은 PG 응답 형식의 400으로 답하고 행을 만들지 않는다")
    void invalidRequests() throws Exception {
        approve(newOrderId(), "EXPLODE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.reason").exists())
                .andExpect(jsonPath("$.code").doesNotExist());
        approve(" ", "SUCCESS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"));
        approve("x".repeat(41), "SUCCESS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + newOrderId() + "\",\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"));
        mockMvc.perform(post(BASE + "/" + "x".repeat(41) + "/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_REQUEST"));
        assertThat(sleeper.calls()).isEmpty();
    }

    private ResultActions approve(String orderId, String scenario) throws Exception {
        return mockMvc.perform(post(BASE)
                .header(HttpNames.PG_SCENARIO_HEADER, scenario)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\",\"amount\":1000}"));
    }

    // 승인 지연 도중 앱이 죽은 상태: PROCESSING 행만 남고 결과를 쓸 스레드가 없다
    private String insertProcessing(long ageSeconds) {
        String orderId = newOrderId();
        jdbcTemplate.update("INSERT INTO fake_pg_transaction (order_id, amount, status, pg_tx_id, created_at, "
                + "updated_at) VALUES (?, 1000, 'PROCESSING', NULL, NOW(6) - INTERVAL ? SECOND, "
                + "NOW(6) - INTERVAL ? SECOND)", orderId, ageSeconds, ageSeconds);
        return orderId;
    }

    private String rowStatus(String orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM fake_pg_transaction WHERE order_id = ?",
                String.class, orderId);
    }

    private static String newOrderId() {
        return "h-" + UUID.randomUUID();
    }
}
