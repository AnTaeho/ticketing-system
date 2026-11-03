package com.example.ticketing.fakepg.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// 부하 테스트와 결제 대사가 기대는 오래된 PROCESSING 처리만 HTTP로 본다. 나머지 규칙은 FakePgServiceTest가 본다
@IntegrationTest
class FakePgControllerTest {

    private static final String BASE = "/fake-pg/payments";

    @Autowired
    private TestMvc testMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties properties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
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
                .andExpect(jsonPath("$.orderId").value(recent))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.pgTxId").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
        assertThat(rowStatus(recent)).isEqualTo("PROCESSING");
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
